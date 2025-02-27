package common.tileentities;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.*;
import static common.itemBlocks.IB_LapotronicEnergyUnit.*;
import static gregtech.api.enums.GT_HatchElement.Maintenance;
import static gregtech.api.util.GT_StructureUtility.buildHatchAdder;
import static gregtech.api.util.GT_StructureUtility.filterByMTEClass;
import static java.lang.Math.min;

import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.*;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.bartimaeusnek.bartworks.API.BorosilicateGlass;
import com.github.technus.tectech.thing.metaTileEntity.hatch.GT_MetaTileEntity_Hatch_DynamoMulti;
import com.github.technus.tectech.thing.metaTileEntity.hatch.GT_MetaTileEntity_Hatch_DynamoTunnel;
import com.github.technus.tectech.thing.metaTileEntity.hatch.GT_MetaTileEntity_Hatch_EnergyMulti;
import com.github.technus.tectech.thing.metaTileEntity.hatch.GT_MetaTileEntity_Hatch_EnergyTunnel;
import com.google.common.collect.ImmutableList;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignmentLimits;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElement.PlaceResult;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.gtnewhorizon.structurelib.util.ItemStackPredicate.NBTMode;
import common.Blocks;

import gregtech.api.enums.Dyes;
import gregtech.api.enums.GT_Values;
import gregtech.api.enums.Textures.BlockIcons;
import gregtech.api.interfaces.IGlobalWirelessEnergy;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.*;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GT_Multiblock_Tooltip_Builder;
import gregtech.api.util.GT_Utility;
import gregtech.api.util.IGT_HatchAdder;

public class GTMTE_LapotronicSuperCapacitor
        extends GT_MetaTileEntity_EnhancedMultiBlockBase<GTMTE_LapotronicSuperCapacitor>
        implements IGlobalWirelessEnergy, ISurvivalConstructable {

    private enum TopState {
        MayBeTop,
        Top,
        NotTop
    }

    private boolean wireless_mode = false;
    private boolean not_processed_lsc = true;
    private int counter = 1;

    private final Queue<Long> energyInputValues = new LinkedList<>();
    private final Queue<Long> energyOutputValues = new LinkedList<>();

    private long max_passive_drain_eu_per_tick_per_uhv_cap = 1_000_000;
    private long max_passive_drain_eu_per_tick_per_uev_cap = 100_000_000;

    private enum Capacitor {

        IV(2, BigInteger.valueOf(IV_cap_storage)),
        LuV(3, BigInteger.valueOf(LuV_cap_storage)),
        ZPM(4, BigInteger.valueOf(ZPM_cap_storage)),
        UV(5, BigInteger.valueOf(UV_cap_storage)),
        UHV(6, MAX_LONG),
        None(0, BigInteger.ZERO),
        EV(1, BigInteger.valueOf(EV_cap_storage)),
        UEV(7, MAX_LONG),;

        private final int minimalGlassTier;
        private final BigInteger providedCapacity;
        static final Capacitor[] VALUES = values();
        static final Capacitor[] VALUES_BY_TIER = Arrays.stream(values())
                .sorted(Comparator.comparingInt(Capacitor::getMinimalGlassTier)).toArray(Capacitor[]::new);

        Capacitor(int minimalGlassTier, BigInteger providedCapacity) {
            this.minimalGlassTier = minimalGlassTier;
            this.providedCapacity = providedCapacity;
        }

        public int getMinimalGlassTier() {
            return minimalGlassTier;
        }

        public BigInteger getProvidedCapacity() {
            return providedCapacity;
        }

        public static int getIndexFromGlassTier(int glassTier) {
            for (int index = 0; index < values().length; index++) {
                if (values()[index].getMinimalGlassTier() == glassTier) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static final String STRUCTURE_PIECE_BASE = "base";
    private static final String STRUCTURE_PIECE_LAYER = "slice";
    private static final String STRUCTURE_PIECE_TOP = "top";
    private static final String STRUCTURE_PIECE_MID = "mid";
    private static final int GLASS_TIER_UNSET = -2;

    private static final Block LSC_PART = Blocks.lscLapotronicEnergyUnit;
    private static final Item LSC_PART_ITEM = Item.getItemFromBlock(LSC_PART);
    private static final int CASING_META = 0;
    private static final int CASING_TEXTURE_ID = (42 << 7) | 127;

    private static final int DURATION_AVERAGE_TICKS = 100;

    // height channel for height.
    // glass channel for glass
    // capacitor channel for capacitor, but it really just pick whatever capacitor it can find in survival
    private static final IStructureDefinition<GTMTE_LapotronicSuperCapacitor> STRUCTURE_DEFINITION = IStructureDefinition
            .<GTMTE_LapotronicSuperCapacitor>builder()
            .addShape(
                    STRUCTURE_PIECE_BASE,
                    transpose(
                            new String[][] { { "bbbbb", "bbbbb", "bbbbb", "bbbbb", "bbbbb", },
                                    { "bb~bb", "bbbbb", "bbbbb", "bbbbb", "bbbbb", }, }))
            .addShape(
                    STRUCTURE_PIECE_LAYER,
                    transpose(new String[][] { { "ggggg", "gcccg", "gcccg", "gcccg", "ggggg", }, }))
            .addShape(
                    STRUCTURE_PIECE_TOP,
                    transpose(new String[][] { { "ggggg", "ggggg", "ggggg", "ggggg", "ggggg", }, }))
            .addShape(
                    STRUCTURE_PIECE_MID,
                    transpose(new String[][] { { "ggggg", "gCCCg", "gCCCg", "gCCCg", "ggggg", }, }))
            .addElement(
                    'b',
                    buildHatchAdder(GTMTE_LapotronicSuperCapacitor.class)
                            .atLeast(LSCHatchElement.Energy, LSCHatchElement.Dynamo, Maintenance)
                            .hatchItemFilterAnd(
                                    (t, h) -> ChannelDataAccessor.getChannelData(h, "glass") < 6
                                            ? filterByMTEClass(
                                                    ImmutableList.of(
                                                            GT_MetaTileEntity_Hatch_EnergyTunnel.class,
                                                            GT_MetaTileEntity_Hatch_DynamoTunnel.class)).negate()
                                            : s -> true)
                            .casingIndex(CASING_TEXTURE_ID).dot(1)
                            .buildAndChain(onElementPass(te -> te.casingAmount++, ofBlock(LSC_PART, CASING_META))))
            .addElement(
                    'g',
                    withChannel(
                            "glass",
                            BorosilicateGlass.ofBoroGlass(
                                    (byte) GLASS_TIER_UNSET,
                                    (te, t) -> te.glassTier = t,
                                    te -> te.glassTier)))
            .addElement(
                    'c',
                    ofChain(
                            onlyIf(
                                    te -> te.topState != TopState.NotTop,
                                    onElementPass(
                                            te -> te.topState = TopState.Top,
                                            withChannel(
                                                    "glass",
                                                    BorosilicateGlass.ofBoroGlass(
                                                            (byte) GLASS_TIER_UNSET,
                                                            (te, t) -> te.glassTier = t,
                                                            te -> te.glassTier)))),
                            onlyIf(
                                    te -> te.topState != TopState.Top,
                                    onElementPass(
                                            te -> te.topState = TopState.NotTop,
                                            new IStructureElement<GTMTE_LapotronicSuperCapacitor>() {

                                                @Override
                                                public boolean check(GTMTE_LapotronicSuperCapacitor t, World world,
                                                        int x, int y, int z) {
                                                    Block worldBlock = world.getBlock(x, y, z);
                                                    int meta = worldBlock.getDamageValue(world, x, y, z);
                                                    if (LSC_PART != worldBlock || meta == 0) return false;
                                                    t.capacitors[meta - 1]++;
                                                    return true;
                                                }

                                                private int getHint(ItemStack stack) {
                                                    return Capacitor.VALUES_BY_TIER[Math.min(
                                                            Capacitor.VALUES_BY_TIER.length,
                                                            ChannelDataAccessor.getChannelData(stack, "capacitor")) - 1]
                                                                    .getMinimalGlassTier()
                                                            + 1;
                                                }

                                                @Override
                                                public boolean spawnHint(GTMTE_LapotronicSuperCapacitor t, World world,
                                                        int x, int y, int z, ItemStack trigger) {
                                                    StructureLibAPI
                                                            .hintParticle(world, x, y, z, LSC_PART, getHint(trigger));
                                                    return true;
                                                }

                                                @Override
                                                public boolean placeBlock(GTMTE_LapotronicSuperCapacitor t, World world,
                                                        int x, int y, int z, ItemStack trigger) {
                                                    world.setBlock(x, y, z, LSC_PART, getHint(trigger), 3);
                                                    return true;
                                                }

                                                @Override
                                                public PlaceResult survivalPlaceBlock(GTMTE_LapotronicSuperCapacitor t,
                                                        World world, int x, int y, int z, ItemStack trigger,
                                                        IItemSource source, EntityPlayerMP actor,
                                                        Consumer<IChatComponent> chatter) {
                                                    if (check(t, world, x, y, z)) return PlaceResult.SKIP;
                                                    int glassTier = ChannelDataAccessor.getChannelData(trigger, "glass")
                                                            + 2;
                                                    ItemStack targetStack = source.takeOne(
                                                            s -> s != null && s.stackSize >= 0
                                                                    && s.getItem() == LSC_PART_ITEM
                                                                    && Capacitor.VALUES[Math.min(
                                                                            s.getItemDamage(),
                                                                            Capacitor.VALUES.length) - 1]
                                                                                    .getMinimalGlassTier()
                                                                            > glassTier,
                                                            true);
                                                    if (targetStack == null) return PlaceResult.REJECT;
                                                    return StructureUtility.survivalPlaceBlock(
                                                            targetStack,
                                                            NBTMode.EXACT,
                                                            targetStack.stackTagCompound,
                                                            true,
                                                            world,
                                                            x,
                                                            y,
                                                            z,
                                                            source,
                                                            actor,
                                                            chatter);
                                                }
                                            }))))
            .addElement('C', ofBlock(LSC_PART, 1)).build();

    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private final Set<GT_MetaTileEntity_Hatch_EnergyMulti> mEnergyHatchesTT = new HashSet<>();
    private final Set<GT_MetaTileEntity_Hatch_DynamoMulti> mDynamoHatchesTT = new HashSet<>();
    private final Set<GT_MetaTileEntity_Hatch_EnergyTunnel> mEnergyTunnelsTT = new HashSet<>();
    private final Set<GT_MetaTileEntity_Hatch_DynamoTunnel> mDynamoTunnelsTT = new HashSet<>();
    /**
     * Count the amount of capacitors of each tier in each slot. Index = meta - 1
     */
    private final int[] capacitors = new int[8];

    private BigInteger capacity = BigInteger.ZERO;
    private BigInteger stored = BigInteger.ZERO;
    private long passiveDischargeAmount = 0;
    private long inputLastTick = 0;
    private long outputLastTick = 0;
    private int repairStatusCache = 0;

    private byte glassTier = -1;
    private int casingAmount = 0;
    private TopState topState = TopState.MayBeTop;

    private long mMaxEUIn = 0;
    private long mMaxEUOut = 0;

    public GTMTE_LapotronicSuperCapacitor(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public GTMTE_LapotronicSuperCapacitor(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity var1) {
        return new GTMTE_LapotronicSuperCapacitor(super.mName);
    }

    @Override
    public IStructureDefinition<GTMTE_LapotronicSuperCapacitor> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    protected IAlignmentLimits getInitialAlignmentLimits() {
        return (d, r, f) -> d.offsetY == 0 && r.isNotRotated() && !f.isVerticallyFliped();
    }

    private void processInputHatch(GT_MetaTileEntity_Hatch aHatch, int aBaseCasingIndex) {
        mMaxEUIn += aHatch.maxEUInput() * aHatch.maxAmperesIn();
        aHatch.updateTexture(aBaseCasingIndex);
    }

    private void processOutputHatch(GT_MetaTileEntity_Hatch aHatch, int aBaseCasingIndex) {
        mMaxEUOut += aHatch.maxEUOutput() * aHatch.maxAmperesOut();
        aHatch.updateTexture(aBaseCasingIndex);
    }

    private boolean addBottomHatches(IGregTechTileEntity aTileEntity, int aBaseCasingIndex) {
        if (aTileEntity == null || aTileEntity.isDead()) return false;
        IMetaTileEntity aMetaTileEntity = aTileEntity.getMetaTileEntity();
        if (!(aMetaTileEntity instanceof GT_MetaTileEntity_Hatch)) return false;
        if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_Maintenance) {
            ((GT_MetaTileEntity_Hatch) aMetaTileEntity).updateTexture(aBaseCasingIndex);
            return GTMTE_LapotronicSuperCapacitor.this.mMaintenanceHatches
                    .add((GT_MetaTileEntity_Hatch_Maintenance) aMetaTileEntity);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_Energy) {
            // Add GT hatches
            final GT_MetaTileEntity_Hatch_Energy tHatch = ((GT_MetaTileEntity_Hatch_Energy) aMetaTileEntity);
            processInputHatch(tHatch, aBaseCasingIndex);
            return mEnergyHatches.add(tHatch);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_EnergyTunnel) {
            // Add TT Laser hatches
            final GT_MetaTileEntity_Hatch_EnergyTunnel tHatch = ((GT_MetaTileEntity_Hatch_EnergyTunnel) aMetaTileEntity);
            processInputHatch(tHatch, aBaseCasingIndex);
            return mEnergyTunnelsTT.add(tHatch);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_EnergyMulti) {
            // Add TT hatches
            final GT_MetaTileEntity_Hatch_EnergyMulti tHatch = (GT_MetaTileEntity_Hatch_EnergyMulti) aMetaTileEntity;
            processInputHatch(tHatch, aBaseCasingIndex);
            return mEnergyHatchesTT.add(tHatch);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_Dynamo) {
            // Add GT hatches
            final GT_MetaTileEntity_Hatch_Dynamo tDynamo = (GT_MetaTileEntity_Hatch_Dynamo) aMetaTileEntity;
            processOutputHatch(tDynamo, aBaseCasingIndex);
            return mDynamoHatches.add(tDynamo);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_DynamoTunnel) {
            // Add TT Laser hatches
            final GT_MetaTileEntity_Hatch_DynamoTunnel tDynamo = (GT_MetaTileEntity_Hatch_DynamoTunnel) aMetaTileEntity;
            processOutputHatch(tDynamo, aBaseCasingIndex);
            return mDynamoTunnelsTT.add(tDynamo);
        } else if (aMetaTileEntity instanceof GT_MetaTileEntity_Hatch_DynamoMulti) {
            // Add TT hatches
            final GT_MetaTileEntity_Hatch_DynamoMulti tDynamo = (GT_MetaTileEntity_Hatch_DynamoMulti) aMetaTileEntity;
            processOutputHatch(tDynamo, aBaseCasingIndex);
            return mDynamoHatchesTT.add(tDynamo);
        }
        return false;
    }

    private int getUHVCapacitorCount() {
        return capacitors[4];
    }

    private int getUEVCapacitorCount() {
        return capacitors[7];
    }

    @Override
    protected GT_Multiblock_Tooltip_Builder createTooltip() {
        final GT_Multiblock_Tooltip_Builder tt = new GT_Multiblock_Tooltip_Builder();
        tt.addMachineType("Energy Storage").addInfo("Loses energy equal to 1% of the total capacity every 24 hours.")
                .addInfo(
                        "Capped at " + EnumChatFormatting.RED
                                + GT_Utility.formatNumbers(max_passive_drain_eu_per_tick_per_uhv_cap)
                                + EnumChatFormatting.GRAY
                                + " EU/t passive loss per "
                                + GT_Values.TIER_COLORS[9]
                                + GT_Values.VN[9]
                                + EnumChatFormatting.GRAY
                                + " capacitor and ")
                .addInfo(
                        EnumChatFormatting.RED + GT_Utility.formatNumbers(max_passive_drain_eu_per_tick_per_uev_cap)
                                + EnumChatFormatting.GRAY
                                + " EU/t passive loss per "
                                + GT_Values.TIER_COLORS[10]
                                + GT_Values.VN[10]
                                + EnumChatFormatting.GRAY
                                + " capacitor.")
                .addInfo("Passive loss is multiplied by the number of maintenance issues present.").addSeparator()
                .addInfo("Glass shell has to be Tier - 3 of the highest capacitor tier.")
                .addInfo(
                        GT_Values.TIER_COLORS[8] + GT_Values.VN[8]
                                + EnumChatFormatting.GRAY
                                + "-tier glass required for "
                                + EnumChatFormatting.BLUE
                                + "Tec"
                                + EnumChatFormatting.DARK_BLUE
                                + "Tech"
                                + EnumChatFormatting.GRAY
                                + " Laser Hatches.")
                .addInfo("Add more or better capacitors to increase capacity.").addSeparator()
                .addInfo("Wireless mode can be enabled by right clicking with a screwdriver.")
                .addInfo(
                        "This mode can only be enabled if you have a " + GT_Values.TIER_COLORS[9]
                                + GT_Values.VN[9]
                                + EnumChatFormatting.GRAY
                                + " or "
                                + GT_Values.TIER_COLORS[10]
                                + GT_Values.VN[10]
                                + EnumChatFormatting.GRAY
                                + " capacitor in the multiblock.")
                .addInfo(
                        "When enabled every " + EnumChatFormatting.BLUE
                                + GT_Utility.formatNumbers(LSC_time_between_wireless_rebalance_in_ticks)
                                + EnumChatFormatting.GRAY
                                + " ticks the LSC will attempt to re-balance against your")
                .addInfo("wireless EU network.")
                .addInfo(
                        "If there is less than " + EnumChatFormatting.RED
                                + GT_Utility.formatNumbers(LSC_wireless_eu_cap)
                                + EnumChatFormatting.GRAY
                                + "("
                                + GT_Values.TIER_COLORS[9]
                                + GT_Values.VN[9]
                                + EnumChatFormatting.GRAY
                                + ")"
                                + " or "
                                + EnumChatFormatting.RED
                                + GT_Utility.formatNumbers(UEV_wireless_eu_cap)
                                + EnumChatFormatting.GRAY
                                + "("
                                + GT_Values.TIER_COLORS[10]
                                + GT_Values.VN[10]
                                + EnumChatFormatting.GRAY
                                + ")"
                                + " EU in the LSC")
                .addInfo("it will withdraw from the network and add to the LSC. If there is more it will add")
                .addInfo("the EU to the network and remove it from the LSC.").addSeparator()
                .beginVariableStructureBlock(5, 5, 4, 50, 5, 5, false)
                .addStructureInfo("Modular height of 4-50 blocks.").addController("Front center bottom")
                .addOtherStructurePart("Lapotronic Super Capacitor Casing", "5x2x5 base (at least 17x)")
                .addOtherStructurePart(
                        "Lapotronic Capacitor (" + GT_Values.TIER_COLORS[4]
                                + GT_Values.VN[4]
                                + EnumChatFormatting.GRAY
                                + "-"
                                + GT_Values.TIER_COLORS[8]
                                + GT_Values.VN[8]
                                + EnumChatFormatting.GRAY
                                + "), Ultimate Capacitor ("
                                + GT_Values.TIER_COLORS[9]
                                + GT_Values.VN[9]
                                + EnumChatFormatting.GRAY
                                + "-"
                                + GT_Values.TIER_COLORS[10]
                                + GT_Values.VN[10]
                                + EnumChatFormatting.GRAY
                                + ")",
                        "Center 3x(1-47)x3 above base (9-423 blocks)")
                .addStructureInfo(
                        "You can also use the Empty Capacitor to save materials if you use it for less than half the blocks")
                .addOtherStructurePart("Borosilicate Glass (any)", "41-265x, Encase capacitor pillar")
                .addEnergyHatch("Any casing").addDynamoHatch("Any casing")
                .addOtherStructurePart(
                        "Laser Target/Source Hatches",
                        "Any casing, must be using " + GT_Values.TIER_COLORS[8]
                                + GT_Values.VN[8]
                                + EnumChatFormatting.GRAY
                                + "-tier glass")
                .addStructureInfo("You can have several I/O Hatches")
                .addSubChannelUsage("glass", "Borosilicate Glass Tier")
                .addSubChannelUsage("capacitor", "Maximum Capacitor Tier")
                .addSubChannelUsage("height", "Height of structure").addMaintenanceHatch("Any casing")
                .toolTipFinisher("KekzTech");
        return tt;
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, byte aSide, byte aFacing, byte aColorIndex,
            boolean aActive, boolean aRedstone) {
        ITexture[] sTexture = new ITexture[] {
                TextureFactory.of(BlockIcons.MACHINE_CASING_FUSION_GLASS, Dyes.getModulation(-1, Dyes._NULL.mRGBa)) };
        if (aSide == aFacing && aActive) {
            sTexture = new ITexture[] { TextureFactory
                    .of(BlockIcons.MACHINE_CASING_FUSION_GLASS_YELLOW, Dyes.getModulation(-1, Dyes._NULL.mRGBa)) };
        }
        return sTexture;
    }

    private String global_energy_user_uuid;

    @Override
    public void onPreTick(IGregTechTileEntity tileEntity, long aTick) {
        super.onPreTick(tileEntity, aTick);

        // On first tick (aTick restarts from 0 upon world reload).
        if (not_processed_lsc && tileEntity.isServerSide()) {
            // Add user to wireless network.
            strongCheckOrAddUser(tileEntity.getOwnerUuid(), tileEntity.getOwnerName());

            // Get team UUID.
            global_energy_user_uuid = getUUIDFromUsername(tileEntity.getOwnerName());

            not_processed_lsc = false;
        }
    }

    @Override
    public boolean isCorrectMachinePart(ItemStack stack) {
        return true;
    }

    @Override
    public boolean checkRecipe(ItemStack stack) {
        this.mProgresstime = 1;
        this.mMaxProgresstime = 1;
        this.mEUt = 0;
        this.mEfficiencyIncrease = 10000;
        return true;
    }

    @Override
    public boolean checkMachine(IGregTechTileEntity thisController, ItemStack guiSlotItem) {
        strongCheckOrAddUser(thisController.getOwnerUuid(), thisController.getOwnerName());

        // Reset capacitor counts
        Arrays.fill(capacitors, 0);
        // Clear TT hatches
        mEnergyHatchesTT.clear();
        mDynamoHatchesTT.clear();
        mEnergyTunnelsTT.clear();
        mDynamoTunnelsTT.clear();

        mMaxEUIn = 0;
        mMaxEUOut = 0;

        glassTier = GLASS_TIER_UNSET;
        casingAmount = 0;

        if (!checkPiece(STRUCTURE_PIECE_BASE, 2, 1, 0)) return false;

        topState = TopState.NotTop; // need at least one layer of capacitor to form, obviously
        int layer = 2;
        while (true) {
            if (!checkPiece(STRUCTURE_PIECE_LAYER, 2, layer, 0)) return false;
            layer++;
            if (topState == TopState.Top) break; // top found, break out
            topState = TopState.MayBeTop;
            if (layer > 50) return false; // too many layers
        }

        // Make sure glass tier is T-2 of the highest tier capacitor in the structure
        // Count down from the highest tier until an entry is found
        // Borosilicate glass after 5 are just recolours of 0
        for (int highestGlassTier = capacitors.length - 1; highestGlassTier >= 0; highestGlassTier--) {
            int highestCapacitor = Capacitor.getIndexFromGlassTier(highestGlassTier);
            if (capacitors[highestCapacitor] > 0) {
                if (Capacitor.VALUES[highestCapacitor].getMinimalGlassTier() > glassTier) return false;
                break;
            }
        }

        // Glass has to be at least UV-tier to allow TT Laser hatches
        if (glassTier < 8) {
            if (mEnergyTunnelsTT.size() > 0 || mDynamoTunnelsTT.size() > 0) return false;
        }

        // Check if enough (more than 50%) non-empty caps
        if (capacitors[6] > capacitors[0] + capacitors[1]
                + capacitors[2]
                + capacitors[3]
                + getUHVCapacitorCount()
                + capacitors[6]
                + getUEVCapacitorCount())
            return false;

        // Calculate total capacity
        capacity = BigInteger.ZERO;
        for (int i = 0; i < capacitors.length; i++) {
            int count = capacitors[i];
            capacity = capacity.add(Capacitor.VALUES[i].getProvidedCapacity().multiply(BigInteger.valueOf(count)));
        }
        // Calculate how much energy to void each tick
        passiveDischargeAmount = recalculateLossWithMaintenance(getRepairStatus());
        return mMaintenanceHatches.size() == 1;
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        int layer = min(stackSize.stackSize + 3, 50);
        buildPiece(STRUCTURE_PIECE_BASE, stackSize, hintsOnly, 2, 1, 0);
        for (int i = 2; i < layer - 1; i++) buildPiece(STRUCTURE_PIECE_MID, stackSize, hintsOnly, 2, i, 0);
        buildPiece(STRUCTURE_PIECE_TOP, stackSize, hintsOnly, 2, layer - 1, 0);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, IItemSource source, EntityPlayerMP actor) {
        if (mMachine) return -1;
        int layer = Math.min(ChannelDataAccessor.getChannelData(stackSize, "height") + 3, 50);
        int built;
        built = survivialBuildPiece(
                STRUCTURE_PIECE_BASE,
                stackSize,
                2,
                1,
                0,
                elementBudget,
                source,
                actor,
                false,
                true);
        if (built >= 0) return built;
        for (int i = 2; i < layer - 1; i++) built = survivialBuildPiece(
                STRUCTURE_PIECE_MID,
                stackSize,
                2,
                i,
                0,
                elementBudget,
                source,
                actor,
                false,
                true);
        if (built >= 0) return built;
        return survivialBuildPiece(
                STRUCTURE_PIECE_TOP,
                stackSize,
                2,
                layer - 1,
                0,
                elementBudget,
                source,
                actor,
                false,
                true);
    }

    @Override
    public boolean onRunningTick(ItemStack stack) {
        // Reset I/O cache
        inputLastTick = 0;
        outputLastTick = 0;

        long temp_stored = 0L;

        // Draw energy from GT hatches
        for (GT_MetaTileEntity_Hatch_Energy eHatch : super.mEnergyHatches) {
            if (eHatch == null || eHatch.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long power = getPowerToDraw(eHatch.maxEUInput() * eHatch.maxAmperesIn());
            if (eHatch.getEUVar() >= power) {
                eHatch.setEUVar(eHatch.getEUVar() - power);
                temp_stored += power;
                inputLastTick += power;
            }
        }

        // Output energy to GT hatches
        for (GT_MetaTileEntity_Hatch_Dynamo eDynamo : super.mDynamoHatches) {
            if (eDynamo == null || eDynamo.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long power = getPowerToPush(eDynamo.maxEUOutput() * eDynamo.maxAmperesOut());
            if (power <= eDynamo.maxEUStore() - eDynamo.getEUVar()) {
                eDynamo.setEUVar(eDynamo.getEUVar() + power);
                temp_stored -= power;
                outputLastTick += power;
            }
        }

        // Draw energy from TT hatches
        for (GT_MetaTileEntity_Hatch_EnergyMulti eHatch : mEnergyHatchesTT) {
            if (eHatch == null || eHatch.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long power = getPowerToDraw(eHatch.maxEUInput() * eHatch.maxAmperesIn());
            if (eHatch.getEUVar() >= power) {
                eHatch.setEUVar(eHatch.getEUVar() - power);
                temp_stored += power;
                inputLastTick += power;
            }
        }

        // Output energy to TT hatches
        for (GT_MetaTileEntity_Hatch_DynamoMulti eDynamo : mDynamoHatchesTT) {
            if (eDynamo == null || eDynamo.getBaseMetaTileEntity() == null
                    || eDynamo.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long power = getPowerToPush(eDynamo.maxEUOutput() * eDynamo.maxAmperesOut());
            if (power <= eDynamo.maxEUStore() - eDynamo.getEUVar()) {
                eDynamo.setEUVar(eDynamo.getEUVar() + power);
                temp_stored -= power;
                outputLastTick += power;
            }
        }

        // Draw energy from TT Laser hatches
        for (GT_MetaTileEntity_Hatch_EnergyTunnel eHatch : mEnergyTunnelsTT) {
            if (eHatch == null || eHatch.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long ttLaserWattage = eHatch.maxEUInput() * eHatch.Amperes - (eHatch.Amperes / 20);
            final long power = getPowerToDraw(ttLaserWattage);
            if (eHatch.getEUVar() >= power) {
                eHatch.setEUVar(eHatch.getEUVar() - power);
                temp_stored += power;
                inputLastTick += power;
            }
        }

        // Output energy to TT Laser hatches
        for (GT_MetaTileEntity_Hatch_DynamoTunnel eDynamo : mDynamoTunnelsTT) {
            if (eDynamo == null || eDynamo.getBaseMetaTileEntity().isInvalidTileEntity()) {
                continue;
            }
            final long ttLaserWattage = eDynamo.maxEUOutput() * eDynamo.Amperes - (eDynamo.Amperes / 20);
            final long power = getPowerToPush(ttLaserWattage);
            if (power <= eDynamo.maxEUStore() - eDynamo.getEUVar()) {
                eDynamo.setEUVar(eDynamo.getEUVar() + power);
                temp_stored -= power;
                outputLastTick += power;
            }
        }

        if (getUHVCapacitorCount() <= 0 && getUEVCapacitorCount() <= 0) {
            wireless_mode = false;
        }

        // Every LSC_time_between_wireless_rebalance_in_ticks check against wireless network for re-balancing.
        counter++;
        if (wireless_mode && (counter >= LSC_time_between_wireless_rebalance_in_ticks)) {

            // Reset tick counter.
            counter = 1;

            // Find difference.
            BigInteger transferred_eu = stored.subtract(
                    (LSC_wireless_eu_cap.multiply(BigInteger.valueOf(getUHVCapacitorCount())))
                            .add(UEV_wireless_eu_cap.multiply(BigInteger.valueOf(getUEVCapacitorCount()))));

            if (transferred_eu.signum() == 1) {
                inputLastTick += transferred_eu.longValue();
            } else {
                outputLastTick += transferred_eu.longValue();
            }

            // If that difference can be added then do so.
            if (addEUToGlobalEnergyMap(global_energy_user_uuid, transferred_eu)) {
                // If it succeeds there was sufficient energy so set the internal capacity as such.
                stored = LSC_wireless_eu_cap.multiply(BigInteger.valueOf(getUHVCapacitorCount()))
                        .add(UEV_wireless_eu_cap.multiply(BigInteger.valueOf(getUEVCapacitorCount())));
            }
        }

        // Lose some energy.
        // Re-calculate if the repair status changed.
        if (super.getRepairStatus() != repairStatusCache) {
            passiveDischargeAmount = recalculateLossWithMaintenance(super.getRepairStatus());
        }

        // This will break if you transfer more than 2^63 EU/t, so don't do that. Thanks <3
        temp_stored -= passiveDischargeAmount;
        stored = stored.add(BigInteger.valueOf(temp_stored));

        // Check that the machine has positive EU stored.
        stored = (stored.compareTo(BigInteger.ZERO) <= 0) ? BigInteger.ZERO : stored;

        IGregTechTileEntity tBMTE = this.getBaseMetaTileEntity();

        tBMTE.injectEnergyUnits((byte) ForgeDirection.UNKNOWN.ordinal(), inputLastTick, 1L);
        tBMTE.drainEnergyUnits((byte) ForgeDirection.UNKNOWN.ordinal(), outputLastTick, 1L);

        // Add I/O values to Queues
        if (energyInputValues.size() > DURATION_AVERAGE_TICKS) {
            energyInputValues.remove();
        }
        energyInputValues.offer(inputLastTick);

        if (energyOutputValues.size() > DURATION_AVERAGE_TICKS) {
            energyOutputValues.remove();
        }

        energyOutputValues.offer(outputLastTick);

        return true;
    }

    /**
     * To be called whenever the maintenance status changes or the capacity was recalculated
     *
     * @param repairStatus This machine's repair status
     * @return new BigInteger instance for passiveDischargeAmount
     */
    private long recalculateLossWithMaintenance(int repairStatus) {
        repairStatusCache = repairStatus;

        // This cannot overflow because there is a 135 capacitor maximum per LSC.
        long temp_capacity_divided = capacity.divide(BigInteger.valueOf(100L * 86400L * 20L)).longValue();

        // Passive loss is multiplied by number of UHV/UEV caps. Minimum of 1 otherwise loss is 0 for non-UHV/UEV caps
        // calculations.
        if (getUHVCapacitorCount() != 0 || getUEVCapacitorCount() != 0) {
            temp_capacity_divided = getUHVCapacitorCount() * max_passive_drain_eu_per_tick_per_uhv_cap
                    + getUEVCapacitorCount() * max_passive_drain_eu_per_tick_per_uev_cap;
        }

        // Passive loss is multiplied by number of maintenance issues.
        long total_passive_loss = temp_capacity_divided * (getIdealStatus() - repairStatus + 1);

        // Maximum of 100,000 EU/t drained per UHV cell. The logic is 1% of EU capacity should be drained every 86400
        // seconds (1 day).
        return total_passive_loss;
    }

    /**
     * Calculate how much EU to draw from an Energy Hatch
     *
     * @param hatchWatts Hatch amperage * voltage
     * @return EU amount
     */
    private long getPowerToDraw(long hatchWatts) {
        final BigInteger remcapActual = capacity.subtract(stored);
        final BigInteger recampLimited = (MAX_LONG.compareTo(remcapActual) > 0) ? remcapActual : MAX_LONG;
        return min(hatchWatts, recampLimited.longValue());
    }

    /**
     * Calculate how much EU to push into a Dynamo Hatch
     *
     * @param hatchWatts Hatch amperage * voltage
     * @return EU amount
     */
    private long getPowerToPush(long hatchWatts) {
        final BigInteger remStoredLimited = (MAX_LONG.compareTo(stored) > 0) ? stored : MAX_LONG;
        return min(hatchWatts, remStoredLimited.longValue());
    }

    private long getAvgIn() {
        long sum = 0L;
        for (long l : energyInputValues) {
            sum += l;
        }
        return sum / Math.max(energyInputValues.size(), 1);
    }

    private long getAvgOut() {
        long sum = 0L;
        for (long l : energyOutputValues) {
            sum += l;
        }
        return sum / Math.max(energyOutputValues.size(), 1);
    }

    @Override
    public String[] getInfoData() {
        NumberFormat nf = NumberFormat.getNumberInstance();
        int secInterval = DURATION_AVERAGE_TICKS / 20;

        final ArrayList<String> ll = new ArrayList<>();
        ll.add(EnumChatFormatting.YELLOW + "Operational Data:" + EnumChatFormatting.RESET);
        ll.add("Used Capacity: " + nf.format(stored) + "EU");
        ll.add("Total Capacity: " + nf.format(capacity) + "EU");
        ll.add("Passive Loss: " + nf.format(passiveDischargeAmount) + "EU/t");
        ll.add("EU IN: " + GT_Utility.formatNumbers(inputLastTick) + "EU/t");
        ll.add("EU OUT: " + GT_Utility.formatNumbers(outputLastTick) + "EU/t");
        ll.add("Avg EU IN: " + nf.format(getAvgIn()) + " (last " + secInterval + " seconds)");
        ll.add("Avg EU OUT: " + nf.format(getAvgOut()) + " (last " + secInterval + " seconds)");
        ll.add(
                "Maintenance Status: " + ((super.getRepairStatus() == super.getIdealStatus())
                        ? EnumChatFormatting.GREEN + "Working perfectly" + EnumChatFormatting.RESET
                        : EnumChatFormatting.RED + "Has Problems" + EnumChatFormatting.RESET));
        ll.add(
                "Wireless mode: " + (wireless_mode ? EnumChatFormatting.GREEN + "enabled" + EnumChatFormatting.RESET
                        : EnumChatFormatting.RED + "disabled" + EnumChatFormatting.RESET));
        ll.add(
                GT_Values.TIER_COLORS[9] + GT_Values.VN[9]
                        + EnumChatFormatting.RESET
                        + " Capacitors detected: "
                        + getUHVCapacitorCount());
        ll.add(
                GT_Values.TIER_COLORS[10] + GT_Values.VN[10]
                        + EnumChatFormatting.RESET
                        + " Capacitors detected: "
                        + getUEVCapacitorCount());
        ll.add(
                "Total wireless EU: " + EnumChatFormatting.RED
                        + GT_Utility.formatNumbers(getUserEU(global_energy_user_uuid)));
        ll.add("---------------------------------------------");

        final String[] a = new String[ll.size()];
        return ll.toArray(a);
    }

    @Override
    public void saveNBTData(NBTTagCompound nbt) {
        nbt = (nbt == null) ? new NBTTagCompound() : nbt;

        nbt.setByteArray("capacity", capacity.toByteArray());
        nbt.setByteArray("stored", stored.toByteArray());
        nbt.setBoolean("wireless_mode", wireless_mode);
        nbt.setInteger("wireless_mode_cooldown", counter);

        super.saveNBTData(nbt);
    }

    @Override
    public void loadNBTData(NBTTagCompound nbt) {
        nbt = (nbt == null) ? new NBTTagCompound() : nbt;

        capacity = new BigInteger(nbt.getByteArray("capacity"));
        stored = new BigInteger(nbt.getByteArray("stored"));
        wireless_mode = nbt.getBoolean("wireless_mode");
        counter = nbt.getInteger("wireless_mode_cooldown");

        super.loadNBTData(nbt);
    }

    @Override
    public boolean isGivingInformation() {
        return true;
    }

    @Override
    public int getMaxEfficiency(ItemStack stack) {
        return 10000;
    }

    @Override
    public int getPollutionPerTick(ItemStack stack) {
        return 0;
    }

    @Override
    public int getDamageToComponent(ItemStack stack) {
        return 0;
    }

    @Override
    public boolean explodesOnComponentBreak(ItemStack stack) {
        return false;
    }

    // called by the getEUCapacity() function in BaseMetaTileEntity
    @Override
    public long maxEUStore() {
        return capacity.longValue();
    }

    // called by the getEUStored() function in BaseMetaTileEntity
    @Override
    public long getEUVar() {
        return stored.longValue();
    }

    /*
     * all of these are needed for the injectEnergyUnits() and drainEnergyUnits() in IGregTechTileEntity
     */
    @Override
    public long maxEUInput() {
        return mMaxEUIn;
    }

    @Override
    public long maxAmperesIn() {
        return 1L;
    }

    @Override
    public long maxEUOutput() {
        return mMaxEUOut;
    }

    @Override
    public long maxAmperesOut() {
        return 1L;
    }

    @Override
    public boolean isEnetInput() {
        return true;
    }

    @Override
    public boolean isEnetOutput() {
        return true;
    }

    @Override
    public void onScrewdriverRightClick(byte aSide, EntityPlayer aPlayer, float aX, float aY, float aZ) {
        if (getUHVCapacitorCount() != 0 || getUEVCapacitorCount() != 0) {
            wireless_mode = !wireless_mode;
            GT_Utility.sendChatToPlayer(aPlayer, "Wireless network mode " + (wireless_mode ? "enabled." : "disabled."));
        } else {
            GT_Utility.sendChatToPlayer(
                    aPlayer,
                    "Wireless mode cannot be enabled without at least 1 " + GT_Values.TIER_COLORS[9]
                            + GT_Values.VN[9]
                            + EnumChatFormatting.RESET
                            + " or "
                            + GT_Values.TIER_COLORS[10]
                            + GT_Values.VN[10]
                            + EnumChatFormatting.RESET
                            + " capacitor.");
            wireless_mode = false;
        }
    }

    private enum LSCHatchElement implements IHatchElement<GTMTE_LapotronicSuperCapacitor> {

        Energy(GT_MetaTileEntity_Hatch_EnergyMulti.class, GT_MetaTileEntity_Hatch_Energy.class) {

            @Override
            public long count(GTMTE_LapotronicSuperCapacitor t) {
                return t.mEnergyHatches.size() + t.mEnergyHatchesTT.size() + t.mEnergyTunnelsTT.size();
            }
        },
        Dynamo(GT_MetaTileEntity_Hatch_DynamoMulti.class, GT_MetaTileEntity_Hatch_Dynamo.class) {

            @Override
            public long count(GTMTE_LapotronicSuperCapacitor t) {
                return t.mDynamoHatches.size() + t.mDynamoHatchesTT.size() + t.mDynamoTunnelsTT.size();
            }
        },;

        private final List<? extends Class<? extends IMetaTileEntity>> mteClasses;

        @SafeVarargs
        LSCHatchElement(Class<? extends IMetaTileEntity>... mteClasses) {
            this.mteClasses = Arrays.asList(mteClasses);
        }

        @Override
        public List<? extends Class<? extends IMetaTileEntity>> mteClasses() {
            return mteClasses;
        }

        @Override
        public IGT_HatchAdder<? super GTMTE_LapotronicSuperCapacitor> adder() {
            return GTMTE_LapotronicSuperCapacitor::addBottomHatches;
        }
    }
}
