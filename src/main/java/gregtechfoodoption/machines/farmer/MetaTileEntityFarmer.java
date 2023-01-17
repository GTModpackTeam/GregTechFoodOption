package gregtechfoodoption.machines.farmer;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Matrix4;
import codechicken.lib.vec.Vector3;
import gregtech.api.GTValues;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.impl.NotifiableItemStackHandler;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.SlotWidget;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.TieredMetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.util.GTTransferUtils;
import gregtech.api.util.GregFakePlayer;
import gregtech.api.util.InventoryUtils;
import gregtech.client.particle.GTParticleManager;
import gregtech.client.renderer.texture.cube.OrientedOverlayRenderer;
import gregtechfoodoption.client.GTFOClientHandler;
import gregtechfoodoption.client.GTFOGuiTextures;
import gregtechfoodoption.client.particle.GTFOFarmingLaserBeamParticle;
import gregtechfoodoption.utils.GTFOUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static gregtech.api.capability.GregtechDataCodes.*;
import static gregtechfoodoption.GTFOValues.UPDATE_OPERATION_POS;

public class MetaTileEntityFarmer extends TieredMetaTileEntity implements IControllable {

    private final int ticksPerAction;
    private AxisAlignedBB workingArea;
    private MutableBlockPos operationPosition;
    public static final int LENGTH = 9;
    private boolean isWorking;
    private boolean isWorkingEnabled = true;
    private FarmerMode cachedMode;
    public FakePlayer fakePlayer;
    private final List<FarmerMode> unusableHarvestingModes = new ArrayList<>();
    protected int seedSlot;

    private static final int BASE_EU_CONSUMPTION = 16;


    public MetaTileEntityFarmer(ResourceLocation metaTileEntityId, int tier, int ticksPerAction) {
        super(metaTileEntityId, tier);
        this.ticksPerAction = ticksPerAction;
        this.initializeInventory();
        cachedMode = FarmerModeRegistry.getAnyMode();
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity iGregTechTileEntity) {
        return new MetaTileEntityFarmer(metaTileEntityId, getTier(), ticksPerAction);
    }

    @Override
    public void update() {
        super.update();
        boolean isWorkingNow = energyContainer.getEnergyStored() >= getEnergyConsumedPerTick();
        if (!getWorld().isRemote && isWorkingNow != isWorking) {
            writeCustomData(IS_WORKING, buffer -> buffer.writeBoolean(isWorkingNow));
            this.isWorking = isWorkingNow;
        }
        if (this.getOffsetTimer() % ticksPerAction != 0 || !isWorking || !isWorkingEnabled)
            return;
        if (this.getWorld().isRemote) {
            GTParticleManager.INSTANCE.addEffect(new GTFOFarmingLaserBeamParticle(getWorld(),
                    new Vector3(new Vec3d(getPos()).add(GTFOUtils.getScaledFacingVec(getFrontFacing(), .4)).add(.5, .7, .5)),
                    new Vector3(new Vec3d(operationPosition)).add(.5, .0, .5),
                    ticksPerAction * 3));
            updateOperationPosition();
            return;
        }
        if (!isWorkingNow)
            return;
        energyContainer.removeEnergy(getEnergyConsumedPerTick());
        if (operationPosition == null || workingArea == null)
            setupWorkingArea();
        operateServer();
    }

    public void operateServer() {
        boolean didSomething = false;

        if (this.fakePlayer == null)
            this.fakePlayer = GregFakePlayer.get((WorldServer) this.getWorld());

        // Phase 1: move crop pointer and collect crops if there exists enough inventory
        IBlockState blockState = getWorld().getBlockState(operationPosition);
        if (!getWorld().isAirBlock(operationPosition)) {
            boolean canHarvestBlock = true;
            if (!cachedMode.canOperate(blockState, this, GTFOUtils.copy(operationPosition), getWorld())) {
                FarmerMode mode = FarmerModeRegistry.findSuitableFarmerMode(blockState, this, GTFOUtils.copy(operationPosition), getWorld());
                if (mode != null) {
                    cachedMode = mode;
                } else {
                    canHarvestBlock = false;
                }
            }
            if (canHarvestBlock && !unusableHarvestingModes.contains(cachedMode) && !GTFOUtils.isFull(getExportItems())) {
                List<ItemStack> drops = cachedMode.getDrops(blockState, getWorld(), GTFOUtils.copy(operationPosition), this);
                if (GTTransferUtils.addItemsToItemHandler(getExportItems(), true, drops)) {
                    GTTransferUtils.addItemsToItemHandler(getExportItems(), false, drops);
                    cachedMode.harvest(blockState, getWorld(), GTFOUtils.copy(operationPosition), this);
                    didSomething = true;
                } else {
                    unusableHarvestingModes.add(cachedMode);
                    notifiedItemOutputList.clear();
                }
            }
        }

        // If the output inventory has updated and isn't full, use all modes
        if (!unusableHarvestingModes.isEmpty()) {
            if (notifiedItemOutputList.contains(getExportItems()) && !GTFOUtils.isFull(getExportItems())) {
                unusableHarvestingModes.clear();
            }
        }

        // Phase 2: place down seed if possible
        if (isCropSpaceEmpty() && InventoryUtils.getNumberOfEmptySlotsInInventory(getImportItems()) != getImportItems().getSlots()) {
            seedSlot = GTFOUtils.getFirstUnemptyItemSlot(getImportItems(), seedSlot + 1);
            ItemStack seedItem = getImportItems().extractItem(seedSlot, 1, true);
            boolean canPlaceSeed = true;
            if (!cachedMode.canPlaceItem(seedItem) || !cachedMode.canPlaceAt(GTFOUtils.copy(operationPosition), new MutableBlockPos(this.getPos()), this.getFrontFacing(), getWorld())) {
                FarmerMode mode;

                mode = FarmerModeRegistry.findSuitableFarmerMode(seedItem, GTFOUtils.copy(operationPosition), new MutableBlockPos(this.getPos()), this.getFrontFacing(), getWorld());
                if (mode != null) {
                    cachedMode = mode;
                } else {
                    canPlaceSeed = false;

                    if (FarmerModeRegistry.findSuitableFarmerMode(seedItem) == null) {
                        // Move this unusable stack to the output
                        ItemStack junkStack = getImportItems().extractItem(seedSlot, getImportItems().getStackInSlot(seedSlot).getCount(), true);
                        if (GTTransferUtils.addItemsToItemHandler(getExportItems(), true, Collections.singletonList(junkStack))) {
                            GTTransferUtils.addItemsToItemHandler(getExportItems(), false,
                                    Collections.singletonList(getImportItems().extractItem(seedSlot, getImportItems().getStackInSlot(seedSlot).getCount(), false)));
                        }
                    }
                }
            }
            if (canPlaceSeed) {
                EnumActionResult result = cachedMode.place(seedItem, getWorld(), GTFOUtils.copy(operationPosition), this);
                if (result == EnumActionResult.SUCCESS) {
                    getImportItems().extractItem(seedSlot, 1, false);
                    didSomething = true;
                }
            }
        }
        if (didSomething)
            getWorld().playSound(null, getPos().getX() + 0.5, getPos().getY() + 0.5, getPos().getZ() + 0.5,
                    GTFOClientHandler.FARMER_LASER, SoundCategory.BLOCKS, 1.0f, 1.0f);
        updateOperationPosition();
    }

    private boolean isCropSpaceEmpty() {
        if (!FarmerModeRegistry.canUseAirOptimization) {
            return true;
        }
        return getWorld().isAirBlock(operationPosition);
    }

    protected int getEnergyConsumedPerTick() {
        return BASE_EU_CONSUMPTION * (1 << ((getTier() - 1) * 2));
    }

    private void updateOperationPosition() {
        operationPosition.move(this.getFrontFacing().rotateYCCW());
        if (!isOperationPositionInsideWorkingArea()) {
            operationPosition.move(this.getFrontFacing().rotateY(), LENGTH).move(this.getFrontFacing());
            if (!isOperationPositionInsideWorkingArea()) {
                setDefaultOperationPosition();
                unusableHarvestingModes.clear(); // Try again in case some other random thing changed
                if (!isOperationPositionInsideWorkingArea() && !getWorld().isRemote) { // This is needed for persistent working areas
                    setupWorkingArea();
                }
            }
        }
    }

    private void setupWorkingArea() {
        workingArea = new AxisAlignedBB(this.getPos().offset(this.getFrontFacing()).offset(this.getFrontFacing().rotateY(), LENGTH / 2),
                this.getPos().offset(this.getFrontFacing(), LENGTH).offset(this.getFrontFacing().rotateYCCW(), LENGTH / 2))
                .grow(.1);
        if (operationPosition == null || !isOperationPositionInsideWorkingArea()) { // The second part is needed due to weirdness in how the facing is set.
            setDefaultOperationPosition();
            writeCustomData(UPDATE_OPERATION_POS, buf -> buf.writeBlockPos(operationPosition));
        }
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == UPDATE_FRONT_FACING) {
            setupWorkingArea();
            setDefaultOperationPosition();
        }
        if (dataId == IS_WORKING) {
            this.isWorking = buf.readBoolean();
            getHolder().scheduleRenderUpdate();
        }
        if (dataId == WORKING_ENABLED) {
            this.isWorkingEnabled = buf.readBoolean();
            getHolder().scheduleRenderUpdate();
        }
        if (dataId == UPDATE_OPERATION_POS) {
            this.operationPosition = new MutableBlockPos(buf.readBlockPos());
            getHolder().scheduleRenderUpdate();
        }
    }

    @Override
    public void setFrontFacing(EnumFacing frontFacing) {
        super.setFrontFacing(frontFacing);
        setupWorkingArea();
        setDefaultOperationPosition();
    }


    protected IItemHandlerModifiable createImportItemHandler() {
        return new ItemStackHandler(9);
    }

    protected IItemHandlerModifiable createExportItemHandler() {
        return new NotifiableItemStackHandler(9, this, true);
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        ModularUI.Builder builder = ModularUI.builder(GuiTextures.BACKGROUND, 176, 166)
                .label(10, 5, this.getMetaFullName());
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int index = i * 3 + j;
                builder.widget(new SlotWidget(this.importItems, index, 28 + j * 18, 18 + i * 18, true, true).setBackgroundTexture(GuiTextures.SLOT, GTFOGuiTextures.SEED_OVERLAY));
                builder.widget(new SlotWidget(this.exportItems, index, 94 + j * 18, 18 + i * 18, true, false).setBackgroundTexture(GuiTextures.SLOT, GTFOGuiTextures.CROP_OVERLAY));
            }
        }

        builder.bindPlayerInventory(entityPlayer.inventory, GuiTextures.SLOT, 7, 84);
        return builder.build(this.getHolder(), entityPlayer);
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        OrientedOverlayRenderer renderer = GTFOClientHandler.FARMER_OVERLAY;
        renderer.renderOrientedState(renderState, translation, pipeline, Cuboid6.full, this.getFrontFacing(), isWorking, isWorkingEnabled);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.isWorking = buf.readBoolean();
        this.operationPosition = new MutableBlockPos(buf.readBlockPos());
        this.isWorkingEnabled = buf.readBoolean();
        setupWorkingArea();
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        setupWorkingArea();
        buf.writeBoolean(isWorking);
        buf.writeBlockPos(operationPosition);
        buf.writeBoolean(isWorkingEnabled);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setLong("operationPosition", operationPosition.toLong());
        data.setBoolean("isWorkingEnabled", isWorkingEnabled);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        operationPosition = new MutableBlockPos(BlockPos.fromLong(data.getLong("operationPosition")));
        isWorkingEnabled = data.getBoolean("isWorkingEnabled");
    }

    private boolean isOperationPositionInsideWorkingArea() {
        return workingArea.contains(new Vec3d(operationPosition.getX(), operationPosition.getY(), operationPosition.getZ()));
    }

    private void setDefaultOperationPosition() {
        operationPosition = new MutableBlockPos(this.getPos().offset(this.getFrontFacing()).offset(this.getFrontFacing().rotateY(), LENGTH / 2));
        writeCustomData(UPDATE_OPERATION_POS, buf -> buf.writeBlockPos(operationPosition));
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(I18n.format("gregtech.universal.tooltip.voltage_in", energyContainer.getInputVoltage(), GTValues.VNF[getTier()]));
        tooltip.add(I18n.format("gregtech.universal.tooltip.energy_storage_capacity", energyContainer.getEnergyCapacity(), GTValues.VNF[getTier()]));
        tooltip.add(I18n.format("gregtechfoodoption.machine.farmer.tooltip.flavor"));
        tooltip.add(I18n.format("gregtechfoodoption.machine.farmer.tooltip.consumption", getEnergyConsumedPerTick()));
        tooltip.add(I18n.format("gregtechfoodoption.machine.farmer.tooltip.working"));
        tooltip.add(I18n.format("gregtechfoodoption.machine.farmer.tooltip.speed", ticksPerAction));
    }

    @Override
    public boolean isWorkingEnabled() {
        return isWorkingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean b) {
        this.isWorkingEnabled = b;
        this.writeCustomData(WORKING_ENABLED, buf -> buf.writeBoolean(b));
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE)
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        return super.getCapability(capability, side);
    }
}
