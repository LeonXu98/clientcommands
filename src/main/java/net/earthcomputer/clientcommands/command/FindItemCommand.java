package net.earthcomputer.clientcommands.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.earthcomputer.clientcommands.GuiBlocker;
import net.earthcomputer.clientcommands.interfaces.IServerCommandSource;
import net.earthcomputer.clientcommands.MathUtil;
import net.earthcomputer.clientcommands.task.LongTask;
import net.earthcomputer.clientcommands.task.TaskManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerProvider;
import net.minecraft.container.Container;
import net.minecraft.container.Slot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.DefaultedList;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static net.earthcomputer.clientcommands.command.ClientCommandManager.*;
import static net.earthcomputer.clientcommands.command.arguments.ClientItemPredicateArgumentType.*;
import static net.earthcomputer.clientcommands.command.arguments.WithStringArgumentType.*;
import static net.minecraft.server.command.CommandManager.*;

public class FindItemCommand {

    private static final int FLAG_NO_SEARCH_SHULKER_BOX = 1;
    private static final int FLAG_KEEP_SEARCHING = 2;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        addClientSideCommand("cfinditem");

        LiteralCommandNode<ServerCommandSource> cfinditem = dispatcher.register(literal("cfinditem"));
        dispatcher.register(literal("cfinditem")
            .then(literal("--no-search-shulker-box")
                .redirect(cfinditem, ctx -> ctx.getSource().withLevel(((IServerCommandSource) ctx.getSource()).getLevel() | FLAG_NO_SEARCH_SHULKER_BOX)))
            .then(literal("--keep-searching")
                .redirect(cfinditem, ctx -> ctx.getSource().withLevel(((IServerCommandSource) ctx.getSource()).getLevel() | FLAG_KEEP_SEARCHING)))
            .then(argument("item", withString(clientItemPredicate()))
                .executes(ctx ->
                        findItem(ctx,
                                (((IServerCommandSource) ctx.getSource()).getLevel() & FLAG_NO_SEARCH_SHULKER_BOX) != 0,
                                (((IServerCommandSource) ctx.getSource()).getLevel() & FLAG_KEEP_SEARCHING) != 0,
                                getWithString(ctx, "item", ItemPredicateArgument.class)))));
    }

    private static int findItem(CommandContext<ServerCommandSource> source, boolean noSearchShulkerBox, boolean keepSearching, Pair<String, ItemPredicateArgument> item) throws CommandSyntaxException {
        String taskName = TaskManager.addTask("cfinditem", new FindItemsTask(item.getLeft(), item.getRight().create(source), !noSearchShulkerBox, keepSearching));
        if (keepSearching) {
            sendFeedback(new TranslatableText("commands.cfinditem.starting.keepSearching", item.getLeft())
                    .append(" ")
                    .append(getCommandTextComponent("commands.client.cancel", "/ctask stop " + taskName)));
        } else {
            sendFeedback(new TranslatableText("commands.cfinditem.starting", item.getLeft()));
        }

        return 0;
    }

    private static class FindItemsTask extends LongTask {
        private final String searchingForName;
        private final Predicate<ItemStack> searchingFor;
        private final boolean searchShulkerBoxes;
        private final boolean keepSearching;

        private int totalFound = 0;
        private Set<BlockPos> searchedBlocks = new HashSet<>();
        private BlockPos currentlySearching = null;
        private int currentlySearchingTimeout;

        public FindItemsTask(String searchingForName, Predicate<ItemStack> searchingFor, boolean searchShulkerBoxes, boolean keepSearching) {
            this.searchingForName = searchingForName;
            this.searchingFor = searchingFor;
            this.searchShulkerBoxes = searchShulkerBoxes;
            this.keepSearching = keepSearching;
        }

        @Override
        public void initialize() {
        }

        @Override
        public boolean condition() {
            return true;
        }

        @Override
        public void increment() {
        }

        @Override
        public void body() {
            World world = MinecraftClient.getInstance().world;
            Entity entity = MinecraftClient.getInstance().cameraEntity;
            if (entity == null) {
                _break();
                return;
            }
            if (currentlySearchingTimeout > 0) {
                currentlySearchingTimeout--;
                scheduleDelay();
                return;
            }
            if (MinecraftClient.getInstance().player.isSneaking()) {
                scheduleDelay();
                return;
            }
            Vec3d origin = entity.getCameraPosVec(0);
            float reachDistance = MinecraftClient.getInstance().interactionManager.getReachDistance();
            int minX = MathHelper.floor(origin.x - reachDistance);
            int minY = MathHelper.floor(origin.y - reachDistance);
            int minZ = MathHelper.floor(origin.z - reachDistance);
            int maxX = MathHelper.floor(origin.x + reachDistance);
            int maxY = MathHelper.floor(origin.y + reachDistance);
            int maxZ = MathHelper.floor(origin.z + reachDistance);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!canSearch(world, pos))
                            continue;
                        if (searchedBlocks.contains(pos))
                            continue;
                        Vec3d closestPos = MathUtil.getClosestPoint(pos, world.getBlockState(pos).getOutlineShape(world, pos), origin);
                        if (closestPos.squaredDistanceTo(origin) > reachDistance * reachDistance)
                            continue;
                        searchedBlocks.add(pos);
                        BlockState state = world.getBlockState(pos);
                        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                            BlockPos offsetPos = pos.offset(ChestBlock.getFacing(state));
                            if (world.getBlockState(offsetPos).getBlock() == state.getBlock())
                                searchedBlocks.add(offsetPos);
                        }
                        startSearch(world, pos, origin, closestPos);
                        scheduleDelay();
                        return;
                    }
                }
            }
            if (!keepSearching)
                _break();
            else
                scheduleDelay();
        }

        private boolean canSearch(World world, BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory) && state.getBlock() != Blocks.ENDER_CHEST)
                return false;
            if (state.getBlock() instanceof ChestBlock || state.getBlock() == Blocks.ENDER_CHEST) {
                if (isChestBlocked(world, pos))
                    return false;
                if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    BlockPos offsetPos = pos.offset(ChestBlock.getFacing(state));
                    if (world.getBlockState(offsetPos).getBlock() == state.getBlock() && isChestBlocked(world, offsetPos))
                        return false;
                }
            }
            return true;
        }

        private static boolean isChestBlocked(World world, BlockPos pos) {
            if (world.getBlockState(pos.up()).isSimpleFullBlock(world, pos.up()))
                return true;
            List<CatEntity> cats = world.getNonSpectatingEntities(CatEntity.class, new Box(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1));
            for (CatEntity cat : cats) {
                if (cat.isSitting())
                    return true;
            }
            return false;
        }

        private void startSearch(World world, BlockPos pos, Vec3d cameraPos, Vec3d clickPos) {
            MinecraftClient mc = MinecraftClient.getInstance();
            currentlySearching = pos;
            currentlySearchingTimeout = 100;
            GuiBlocker.addBlocker(new GuiBlocker() {
                @Override
                public boolean accept(Screen screen) {
                    if (!(screen instanceof ContainerProvider))
                        return true;
                    Container container = ((ContainerProvider) screen).getContainer();
                    Set<Integer> playerInvSlots = new HashSet<>();
                    for (Slot slot : container.slotList)
                        if (slot.inventory instanceof PlayerInventory)
                            playerInvSlots.add(slot.id);
                    MinecraftClient.getInstance().player.container = new Container(container.getType(), container.syncId) {
                        @Override
                        public boolean canUse(PlayerEntity var1) {
                            return true;
                        }

                        @Override
                        public void updateSlotStacks(List<ItemStack> stacks) {
                            int matchingItems = 0;
                            for (int slot = 0; slot < stacks.size(); slot++) {
                                if (playerInvSlots.contains(slot))
                                    continue;
                                ItemStack stack = stacks.get(slot);
                                if (searchingFor.test(stack))
                                    matchingItems += stack.getCount();
                                if (searchShulkerBoxes && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock) {
                                    CompoundTag blockEntityTag = stack.getSubTag("BlockEntityTag");
                                    if (blockEntityTag != null && blockEntityTag.contains("Items")) {
                                        DefaultedList<ItemStack> boxInv = DefaultedList.ofSize(27, ItemStack.EMPTY);
                                        Inventories.fromTag(blockEntityTag, boxInv);
                                        for (ItemStack stackInBox : boxInv) {
                                            if (searchingFor.test(stackInBox)) {
                                                matchingItems += stackInBox.getCount();
                                            }
                                        }
                                    }
                                }
                            }
                            if (matchingItems > 0) {
                                sendFeedback(new TranslatableText("commands.cfinditem.match.left", matchingItems, searchingForName)
                                        .append(getCoordsTextComponent(currentlySearching))
                                        .append(new TranslatableText("commands.cfinditem.match.right", matchingItems, searchingForName)));
                                totalFound += matchingItems;
                            }
                            currentlySearching = null;
                            currentlySearchingTimeout = 0;
                            MinecraftClient.getInstance().player.closeContainer();
                        }
                    };
                    return false;
                }
            });
            mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND,
                    new BlockHitResult(clickPos,
                            Direction.getFacing((float) (clickPos.x - cameraPos.x), (float) (clickPos.y - cameraPos.y), (float) (clickPos.z - cameraPos.z)),
                            pos, false));
        }

        @Override
        public void onCompleted() {
            sendFeedback(new TranslatableText("commands.cfinditem.total", totalFound, searchingForName).formatted(Formatting.BOLD));
        }
    }
}
