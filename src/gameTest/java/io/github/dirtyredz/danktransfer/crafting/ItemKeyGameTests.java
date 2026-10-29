package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * {@link ItemKey} over real data components (needs registries, hence a GameTest): the planner's
 * identity must match vanilla stacking exactly, components included and count excluded.
 */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemKeyGameTests {
    private static final String TEMPLATE = "empty";

    private ItemKeyGameTests() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private static void distinct(ItemStack a, ItemStack b, String what) {
        ItemKey ka = ItemKey.of(a);
        ItemKey kb = ItemKey.of(b);
        check(!ka.equals(kb) && !kb.equals(ka), what + ": keys must differ");
        check(!ka.matches(b) && !kb.matches(a), what + ": matches must agree with equals");
    }

    private static void same(ItemStack a, ItemStack b, String what) {
        ItemKey ka = ItemKey.of(a);
        ItemKey kb = ItemKey.of(b);
        check(ka.equals(kb) && kb.equals(ka), what + ": keys must be equal");
        check(ka.hashCode() == kb.hashCode(), what + ": equal keys must hash equally");
        check(ka.matches(b) && kb.matches(a), what + ": matches must agree with equals");
        check(ItemStack.isSameItemSameComponents(a, b), what + ": vanilla must stack them too");
    }

    @GameTest(template = TEMPLATE)
    public static void componentsAreIdentityAndCountIsNot(GameTestHelper helper) {
        ItemStack plain = new ItemStack(Items.OAK_PLANKS, 3);
        same(plain, new ItemStack(Items.OAK_PLANKS, 64), "count is not identity");

        ItemStack named = new ItemStack(Items.OAK_PLANKS);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        distinct(plain, named, "custom name");
        ItemStack namedAgain = new ItemStack(Items.OAK_PLANKS, 9);
        namedAgain.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        same(named, namedAgain, "same name");

        CompoundTag tag = new CompoundTag();
        tag.putString("origin", "test");
        ItemStack custom = new ItemStack(Items.OAK_PLANKS);
        custom.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        distinct(plain, custom, "custom data");
        distinct(named, custom, "different components");

        ItemStack enchanted = new ItemStack(Items.IRON_SWORD);
        enchanted.enchant(helper.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS), 2);
        ItemStack strongerEnchanted = new ItemStack(Items.IRON_SWORD);
        strongerEnchanted.enchant(helper.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS), 3);
        distinct(new ItemStack(Items.IRON_SWORD), enchanted, "enchantment");
        distinct(enchanted, strongerEnchanted, "enchantment level");

        ItemStack damaged = new ItemStack(Items.IRON_SWORD);
        damaged.setDamageValue(5);
        distinct(new ItemStack(Items.IRON_SWORD), damaged, "damage");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void removedDefaultComponentsCount(GameTestHelper helper) {
        ItemStack apple = new ItemStack(Items.APPLE);
        check(apple.has(DataComponents.FOOD), "test sanity: apples are food by default");
        ItemStack inedible = new ItemStack(Items.APPLE);
        inedible.remove(DataComponents.FOOD);
        check(!inedible.has(DataComponents.FOOD), "test sanity: removal took effect");
        distinct(apple, inedible, "removed default component");
        ItemStack alsoInedible = new ItemStack(Items.APPLE, 7);
        alsoInedible.remove(DataComponents.FOOD);
        same(inedible, alsoInedible, "same removal");
        check(!ItemKey.of(inedible).toStack(1).has(DataComponents.FOOD), "the removal survives toStack");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void keysCopyTheirSourceAndHandOutCopies(GameTestHelper helper) {
        ItemStack source = new ItemStack(Items.OAK_PLANKS, 5);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("Before"));
        ItemKey key = ItemKey.of(source);
        int hash = key.hashCode();

        source.set(DataComponents.CUSTOM_NAME, Component.literal("After"));
        source.setCount(1);
        check(key.hashCode() == hash, "mutating the source stack must not change the key's hash");
        check(!key.matches(source), "the key kept the original components");

        ItemStack handedOut = key.toStack(12);
        check(handedOut.getCount() == 12, "toStack sets the requested count");
        handedOut.set(DataComponents.CUSTOM_NAME, Component.literal("Mutated"));
        ItemStack again = key.toStack(1);
        check("Before".equals(again.getHoverName().getString()), "toStack hands out independent copies: " + again.getHoverName());
        check(key.probe().getCount() == 1, "the probe stays count-1");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void stackLimitsAreComponentAware(GameTestHelper helper) {
        check(ItemKey.of(new ItemStack(Items.OAK_PLANKS)).maxStackSize() == 64, "planks stack to 64");
        check(ItemKey.of(new ItemStack(Items.ENDER_PEARL)).maxStackSize() == 16, "pearls stack to 16");
        ItemStack small = new ItemStack(Items.OAK_PLANKS);
        small.set(DataComponents.MAX_STACK_SIZE, 8);
        check(ItemKey.of(small).maxStackSize() == 8, "the max stack size component is honoured");
        distinct(new ItemStack(Items.OAK_PLANKS), small, "max stack size component");
        for (ItemKey key : List.of(ItemKey.of(small), ItemKey.of(new ItemStack(Items.IRON_SWORD)))) {
            check(key.toStack(1).getMaxStackSize() == key.maxStackSize(), "key and stacks agree on the limit");
        }
        helper.succeed();
    }
}
