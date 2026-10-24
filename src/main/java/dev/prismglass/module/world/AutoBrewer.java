package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.packs.VanillaBrewingProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.BrewingRecipe;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * While a brewing stand is open: puts water bottles in, adds each ingredient of the chosen potion in turn (nether
 * wart, the effect ingredient, then glowstone / redstone, gunpowder, dragon's breath), keeps it fuelled with blaze
 * powder and takes the finished potions out, then starts over while you have water bottles. The next ingredient is
 * the first one in the recipe that the stand's own brewing rules accept for the bottles in it, so a half-brewed
 * stand carries on from where it is.
 *
 * <p>26.3 made brewing data-driven ({@link BrewingRecipe}) and the client no longer knows the mixes, so they are
 * rebuilt once from vanilla's own {@link VanillaBrewingProvider} (what the built-in data pack is generated from).
 */
public class AutoBrewer extends Module {
    public final ModeSetting potion = mode("Potion", "Strength", "What to brew.",
        "Strength", "Swiftness", "FireResistance", "Healing", "Regeneration", "SlowFalling", "TurtleMaster", "Harming", "Invisibility", "Weakness");
    public final ModeSetting upgrade = mode("Upgrade", "Strong", "Glowstone (II) or redstone (longer), where the potion has one.", "Strong", "Long", "None");
    public final ModeSetting form = mode("Form", "Splash", "Drinkable, splash (gunpowder) or lingering (+ dragon's breath).", "Drink", "Splash", "Lingering");
    public final BoolSetting fuel = bool("Fuel", true, "Keep blaze powder in the fuel slot.");
    public final BoolSetting takeOut = bool("TakeOut", true, "Take the finished potions out and start a new batch.");

    private int wait;
    public int batches;

    private static List<BrewingRecipe> vanillaRecipes;

    public AutoBrewer() { super("AutoBrewer", "Brews the chosen potion while a brewing stand is open.", Category.WORLD); }

    private List<Item> recipe() {
        List<Item> r = new ArrayList<>();
        if (!potion.is("Weakness")) r.add(Items.NETHER_WART);
        switch (potion.get()) {
            case "Strength" -> r.add(Items.BLAZE_POWDER);
            case "Swiftness" -> r.add(Items.SUGAR);
            case "FireResistance" -> r.add(Items.MAGMA_CREAM);
            case "Healing" -> r.add(Items.GLISTERING_MELON_SLICE);
            case "Regeneration" -> r.add(Items.GHAST_TEAR);
            case "SlowFalling" -> r.add(Items.PHANTOM_MEMBRANE);
            case "TurtleMaster" -> r.add(Items.TURTLE_HELMET);
            case "Harming" -> { r.add(Items.SPIDER_EYE); r.add(Items.FERMENTED_SPIDER_EYE); }
            case "Invisibility" -> { r.add(Items.GOLDEN_CARROT); r.add(Items.FERMENTED_SPIDER_EYE); }
            case "Weakness" -> r.add(Items.FERMENTED_SPIDER_EYE);
            default -> {}
        }
        if (upgrade.is("Strong")) r.add(Items.GLOWSTONE_DUST);
        if (upgrade.is("Long")) r.add(Items.REDSTONE);
        if (!form.is("Drink")) r.add(Items.GUNPOWDER);
        if (form.is("Lingering")) r.add(Items.DRAGON_BREATH);
        return r;
    }

    @Override
    public void onTick() {
        if (mc.gui.screen() == null || !(mc.player.containerMenu instanceof BrewingStandMenu menu)) { wait = 0; return; }
        if (wait > 0) { wait--; return; }
        if (!menu.getCarried().isEmpty() || !InvUtil.canClick()) return;

        if (fuel.get() && menu.getFuel() == 0 && menu.getSlot(4).getItem().isEmpty() && one(menu, Items.BLAZE_POWDER, 4)) return;
        if (menu.getBrewingTicks() > 0 || !menu.getSlot(3).getItem().isEmpty()) return; // busy

        List<ItemStack> bottles = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (!menu.getSlot(i).getItem().isEmpty()) bottles.add(menu.getSlot(i).getItem());
        if (bottles.isEmpty()) { loadWater(menu); return; }
        for (Item ing : recipe()) {
            ItemStack test = new ItemStack(ing);
            if (bottles.stream().allMatch(b -> hasMix(b, test))) {
                if (!one(menu, ing, 3)) wait = 20; // out of it: try again in a second
                return;
            }
        }
        // nothing more to add: done
        if (!takeOut.get()) return;
        for (int i = 0; i < 3; i++) if (!menu.getSlot(i).getItem().isEmpty()) { click(menu, i, 0, ContainerInput.QUICK_MOVE); wait = 1; return; }
    }

    /** Old {@code PotionBrewing#hasMix}: some brewing recipe turns this bottle into something with this reagent. */
    private static boolean hasMix(ItemStack bottle, ItemStack reagent) {
        BrewingInput in = new BrewingInput(bottle, reagent);
        for (BrewingRecipe r : recipes()) if (r.matches(in)) return true;
        return false;
    }

    private static synchronized List<BrewingRecipe> recipes() {
        if (vanillaRecipes != null) return vanillaRecipes;
        List<BrewingRecipe> out = new ArrayList<>();
        try {
            // the provider only ever calls accept(id, recipe, advancement) on its output
            RecipeOutput sink = (RecipeOutput) Proxy.newProxyInstance(RecipeOutput.class.getClassLoader(), new Class<?>[]{RecipeOutput.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "accept" -> {
                        if (args != null && args.length == 3 && args[1] instanceof BrewingRecipe r) out.add(r);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PrismBrewingSink";
                    // Fabric's data-gen API injects default methods (getRecipeIdentifier...) that the provider calls
                    default -> {
                        if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
            new VanillaBrewingProvider(sink) {}.buildRecipes();
            Prism.LOG.info("[autobrewer] {} vanilla brewing recipes", out.size());
        } catch (Throwable t) {
            Prism.LOG.error("[autobrewer] could not build the brewing recipes", t);
        }
        return vanillaRecipes = List.copyOf(out);
    }

    private void loadWater(AbstractContainerMenu menu) {
        int put = 0;
        for (Slot s : menu.slots) {
            if (put >= 3 || !(s.container instanceof Inventory) || s.getContainerSlot() >= 36) continue;
            ItemStack st = s.getItem();
            PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
            if (!st.is(Items.POTION) || pc == null || !pc.is(Potions.WATER)) continue;
            click(menu, s.index, 0, ContainerInput.PICKUP);
            click(menu, put, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) click(menu, s.index, 0, ContainerInput.PICKUP);
            put++;
        }
        if (put > 0) { batches++; wait = 2; }
    }

    /** Moves one item of this kind from your inventory into the slot (right-click places one). */
    private boolean one(AbstractContainerMenu menu, Item item, int slot) {
        for (Slot s : menu.slots) {
            if (!(s.container instanceof Inventory) || s.getContainerSlot() >= 36 || !s.getItem().is(item)) continue;
            click(menu, s.index, 0, ContainerInput.PICKUP);
            click(menu, slot, 1, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) click(menu, s.index, 0, ContainerInput.PICKUP);
            wait = 2;
            return true;
        }
        return false;
    }

    private void click(AbstractContainerMenu menu, int slot, int button, ContainerInput in) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, in, mc.player);
    }
}
