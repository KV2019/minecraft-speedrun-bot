package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public final class CraftBasicWoodTask implements BotTask {
    private enum RecipeKind {
        NONE,
        PLANKS,
        CRAFTING_TABLE,
        STICKS
    }

    private static final int RESULT_SLOT = 0;
    private static final int[] TABLE_RECIPE_SLOTS = {1, 2, 3, 4};
    private static final int[] STICK_RECIPE_SLOTS = {1, 3};
    private static final int[] PLANK_RECIPE_SLOTS = {1};
    private static final int DESIRED_STICKS = 8;

    private boolean completed;
    private int actionCooldown;
    private RecipeKind activeRecipe;
    private int recipeStep;

    @Override
    public String name() {
        return "Craft Basic Wood";
    }

    @Override
    public void start(BotContext context) {
        completed = false;
        actionCooldown = 0;
        activeRecipe = RecipeKind.NONE;
        recipeStep = 0;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Crafting basic wood items"), true);
    }

    @Override
    public void tick(BotContext context) {
        if (completed) {
            return;
        }

        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        ScreenHandler handler = context.player().playerScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null || handler == null) {
            return;
        }

        // Only clear stale/manual leftovers while idle. Clearing during an active recipe causes flicker.
        if (activeRecipe == RecipeKind.NONE && !isCraftGridClear(handler)) {
            quickMoveFirstCraftSlot(context, interaction, handler);
            actionCooldown = 2;
            return;
        }

        if (activeRecipe == RecipeKind.NONE) {
            activeRecipe = chooseNextRecipe(context.player());
            recipeStep = 0;

            if (activeRecipe == RecipeKind.NONE) {
                completed = true;
                context.player().sendMessage(Text.literal("[SpeedrunBot] Finished crafting wood items"), true);
                return;
            }
        }

        if (runRecipeStep(context, interaction, handler)) {
            actionCooldown = 2;
        } else {
            activeRecipe = RecipeKind.NONE;
            recipeStep = 0;
            actionCooldown = 2;
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return completed;
    }

    @Override
    public void stop(BotContext context) {
        completed = false;
        actionCooldown = 0;
        activeRecipe = RecipeKind.NONE;
        recipeStep = 0;
    }

    private RecipeKind chooseNextRecipe(ClientPlayerEntity player) {
        if (countTaggedItems(player, ItemTags.LOGS) > 0) {
            return RecipeKind.PLANKS;
        }

        if (countExactItem(player, Items.CRAFTING_TABLE) == 0 && countTaggedItems(player, ItemTags.PLANKS) >= 4) {
            return RecipeKind.CRAFTING_TABLE;
        }

        if (countExactItem(player, Items.STICK) < DESIRED_STICKS && countTaggedItems(player, ItemTags.PLANKS) >= 2) {
            return RecipeKind.STICKS;
        }

        return RecipeKind.NONE;
    }

    private boolean runRecipeStep(BotContext context, ClientPlayerInteractionManager interaction, ScreenHandler handler) {
        int[] recipeSlots = switch (activeRecipe) {
            case PLANKS -> PLANK_RECIPE_SLOTS;
            case CRAFTING_TABLE -> TABLE_RECIPE_SLOTS;
            case STICKS -> STICK_RECIPE_SLOTS;
            case NONE -> new int[0];
        };

        if (activeRecipe == RecipeKind.NONE) {
            return false;
        }

        if (recipeStep < recipeSlots.length) {
            int ingredientSlot = findIngredientSlot(context.player(), activeRecipe);
            if (ingredientSlot == -1) {
                return false;
            }

            placeSingleItem(context, interaction, handler, ingredientSlot, recipeSlots[recipeStep]);
            recipeStep++;
            return true;
        }

        if (handler.getSlot(RESULT_SLOT).getStack().isEmpty()) {
            return false;
        }

        interaction.clickSlot(handler.syncId, RESULT_SLOT, 0, SlotActionType.QUICK_MOVE, context.player());
        activeRecipe = RecipeKind.NONE;
        recipeStep = 0;
        return true;
    }

    private void placeSingleItem(
        BotContext context,
        ClientPlayerInteractionManager interaction,
        ScreenHandler handler,
        int sourceSlot,
        int craftSlot
    ) {
        if (!handler.getCursorStack().isEmpty()) {
            interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, context.player());
        }

        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, context.player());
        interaction.clickSlot(handler.syncId, craftSlot, 1, SlotActionType.PICKUP, context.player());
        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, context.player());
    }

    private void quickMoveFirstCraftSlot(BotContext context, ClientPlayerInteractionManager interaction, ScreenHandler handler) {
        for (int slotId : TABLE_RECIPE_SLOTS) {
            if (!handler.getSlot(slotId).getStack().isEmpty()) {
                interaction.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, context.player());
                return;
            }
        }
    }

    private boolean isCraftGridClear(ScreenHandler handler) {
        for (int slotId : TABLE_RECIPE_SLOTS) {
            if (!handler.getSlot(slotId).getStack().isEmpty()) {
                return false;
            }
        }
        return handler.getCursorStack().isEmpty();
    }

    private int findIngredientSlot(ClientPlayerEntity player, RecipeKind recipe) {
        for (int invIndex = 0; invIndex < player.getInventory().size(); invIndex++) {
            ItemStack stack = player.getInventory().getStack(invIndex);
            if (stack.isEmpty()) {
                continue;
            }

            boolean matches = switch (recipe) {
                case PLANKS -> stack.isIn(ItemTags.LOGS);
                case CRAFTING_TABLE, STICKS -> stack.isIn(ItemTags.PLANKS);
                case NONE -> false;
            };

            if (matches) {
                return playerInventoryIndexToScreenSlot(invIndex);
            }
        }

        return -1;
    }

    private int playerInventoryIndexToScreenSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 36 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return invIndex;
        }
        return -1;
    }

    private int countTaggedItems(ClientPlayerEntity player, net.minecraft.registry.tag.TagKey<Item> tag) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isIn(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countExactItem(ClientPlayerEntity player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}