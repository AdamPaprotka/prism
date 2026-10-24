# Porting Prism Glass 26.1.2 -> 26.3

Notes from the Core pass (branch `port-26.3`). Every mapping below was checked against the decompiled 26.3 sources,
not guessed. "MC" = `Minecraft.getInstance()` / `mc`.

## Helpers added (use these instead of re-deriving the new APIs)

| Helper | What it does |
|---|---|
| `util/KeyUtil` | Bind codes are now **SDL scancodes** (keyboard) and `MOUSE_OFFSET + SDL button` (mouse, 1-based). `MOUSE_LEFT/MIDDLE/RIGHT`, `PRESS/RELEASE/REPEAT`, `isBindableMouse(b)`, `mouse(b)`, `isDown(bind)`, `isKeyDown(scancode)`, `isMouseDown(sdlButton)`, `shiftDown()`, `controlDown()`, `legacyButton(sdl)` (0 left / 1 right / 2 middle, for code that kept the GLFW numbering internally), cursor helpers `cursorLocked/setCursorLocked` (old `GLFW_CURSOR_DISABLED`), `cursorHidden/setCursorHidden` (old `GLFW_CURSOR_HIDDEN`), `setCursorNormal`, `warpCursor` (old `glfwSetCursorPos`), `saveName/fromSaveName` (config form), `fromLegacy(code)` (GLFW code / 0-based GLFW button -> today's code). |
| `setting/KeySetting` | Saves the vanilla key name (`"key.keyboard.r"`, `"key.mouse.middle"`, `"none"`). A bare number in a config / shared profile code is a 1.6.3-or-older GLFW code and is translated once by `KeyUtil.fromLegacy`, so old binds keep meaning the same physical key. User-facing names are unchanged ("Mouse3" is still the wheel). |
| `util/SwingUtil` | `use(hand)`: local swing animation after a use/place/interact (the server swings from the use packet itself). `punch()`: main-hand attack swing + `ServerboundPunchPacket` (attacks, digging, bare punches - what vanilla `startAttack`/`continueAttack` send). `punchPacket()`: packet only (old "silent" swing). |
| `util/EntityUtil.interpolationTarget(e)` | Newest server position (old `getInterpolation().position()`). |
| `util/BlockUtil.blocksMotion(state)` | 26.1.2's `BlockState.blocksMotion()` (removed). |

## Screen / HUD

| 26.1.2 | 26.3 | Notes |
|---|---|---|
| `mc.screen` | `mc.gui.screen()` | `Minecraft.gui` is now the screen/overlay manager `net.minecraft.client.gui.Gui`. |
| `mc.setScreen(s)` | `mc.gui.setScreen(s)` | Same semantics (null -> game / title / death screen). `mc.setScreenAndShow(s)` = set + force a frame. `Screen.onClose()` still works. |
| `mc.gui` (the HUD, class `Gui`) | `mc.gui.hud` (class `net.minecraft.client.gui.Hud`) | `getChat()`, `getTabList()`, `getBossOverlay()`, `getGuiTicks()`, `setOverlayMessage`, titles... all moved to `Hud`. |
| `mc.gui.getChat()` | `mc.gui.hud.getChat()` | |
| `mc.options.hideGui` (read) | `mc.gui.hud.isHidden()` | Write: `mc.gui.hud.toggle()`. |
| `Gui.extractCrosshair / extractTextureOverlay / extractVignette / extractPortalOverlay` (mixin targets) | same names/signatures on `Hud` | CrosshairMixin, InGameHudMixin retargeted. |
| `Screen.minecraft` | still `protected final Minecraft minecraft`, set in the constructor | |
| `Minecraft.getMainRenderTarget()` | `mc.gameRenderer.mainRenderTarget()` | `Screenshot.grab(dir, name, target, 1, cb)` signature unchanged. |
| `gameRenderer.getMainCamera()` | `gameRenderer.mainCamera()` | `Camera.position()` unchanged. |
| `gameRenderer.getGameRenderState()` | `gameRenderer.gameRenderState()` | |
| `gameRenderer.displayItemActivation(stack)` | `mc.player.displayItemActivation(stack)` | |
| `GameRenderer.getNightVisionScale` | `static GameRenderer.nightVisionScale(LivingEntity, float)` | (GameRendererMixin) |
| `levelRenderer.needsUpdate()` | `levelRenderer.sectionOcclusionGraph().invalidate()` | |
| `Util.getPlatform().openPath/openUri` | `com.mojang.blaze3d.Blaze3D.openPath(Path)` / `openUri(URI)` | (SDL_OpenURL, async) |
| `RenderSystem.getDevice().getRenderer()` | `RenderSystem.getDevice().getDeviceInfo().name()` | also `vendorName()`, `backendName()`, `driverInfo()` |

## Input (GLFW is gone; SDL3)

| 26.1.2 | 26.3 | Notes |
|---|---|---|
| `GLFW.GLFW_KEY_X` | `InputConstants.KEY_X` (SDL **scancode**, compile-time constant, fine in `case`) | ENTER -> `KEY_RETURN`, KP_ENTER -> `KEY_NUMPADENTER`, LEFT/RIGHT_SHIFT -> `KEY_LSHIFT/KEY_RSHIFT`, LEFT/RIGHT_CONTROL -> `KEY_LCONTROL/KEY_RCONTROL`, ESCAPE/BACKSPACE/TAB/DELETE same name. |
| `GLFW.GLFW_KEY_UNKNOWN` (-1) | `0` (`InputConstants.UNKNOWN`) | |
| `GLFW_PRESS/RELEASE/REPEAT` (1/0/2) | `InputConstants.PRESS/RELEASE/REPEAT` (1/0/**-1**) or `KeyUtil.*` | |
| `GLFW_MOD_SHIFT/CONTROL/ALT` (1/2/4) | `InputConstants.MOD_SHIFT/MOD_CONTROL/MOD_ALT` (3/192/768) | prefer `event.hasShiftDown()/hasControlDown()/isPaste()/isCopy()...` |
| `GLFW_MOUSE_BUTTON_LEFT/RIGHT/MIDDLE` (0/1/2) | `InputConstants.MOUSE_BUTTON_LEFT/RIGHT/MIDDLE` (**1/3/2**) | **Silent trap:** `event.button() == 0/1` compiles but is wrong now. Use `KeyUtil.MOUSE_LEFT/RIGHT/MIDDLE` or `KeyUtil.legacyButton(event.button())`. |
| `new KeyEvent(glfwKey, scancode, mods)` | `new KeyEvent(scancode, sdlKeycode, mods)` | `key()` is the scancode; `keycode()`/`shortcutKey()` the layout keycode (`InputConstants.KEYCODE_*`). |
| `InputWithModifiers.getDigit()` | removed | use `InputConstants.Key.getNumericKeyValue()` |
| `InputConstants.isKeyDown(window, key)` | `InputConstants.isKeyDown(scancode)` | or `KeyUtil.isKeyDown`. |
| `InputConstants.Type.KEYSYM` / `SCANCODE` | `InputConstants.Type.KEYBOARD` | key names (`key.keyboard.a`) are unchanged, so vanilla `options.txt` binds carry over. |
| `glfwGetKeyName` | `SDLKeyboard.SDL_GetKeyName(SDL_GetKeyFromScancode(sc, (short) 0, false))` | inside `KeyUtil.name`. |
| `glfwGetMouseButton(h, b)` | `KeyUtil.isMouseDown(sdlButton)` (`SDL_GetMouseState`) | |
| `glfwGetInputMode/SetInputMode(GLFW_CURSOR, ...)` | `KeyUtil.cursorLocked/setCursorLocked/setCursorHidden/setCursorNormal` | SDL relative mouse mode = locked cursor. |
| `glfwSetCursorPos` | `KeyUtil.warpCursor(window, x, y)` | |
| `glfwGetWindowAttrib(h, VISIBLE/FOCUSED)` | `SDLVideo.SDL_GetWindowFlags(h) & SDL_WINDOW_HIDDEN / SDL_WINDOW_INPUT_FOCUS` | |
| `KeyboardHandler.keyPress(long, int, KeyEvent)` | unchanged signature | KeyboardMixin. |
| `MouseHandler.onButton(long, MouseButtonInfo, int)` | unchanged signature, SDL button numbers | MouseMixin. |
| `MouseHandler.onMove(long, double x, double y)` | `onMove(long, double x, double y, double xrel, double yrel)` | In relative (locked) mode only `xrel/yrel` change. |
| `keyboardHandler.getClipboard()` | unchanged | (backed by `ClipboardManager`) |

Window code (Window agent): `Window.handle()` is the SDL_Window pointer; `Window.isFullscreen()` and
`toggleFullScreen()` are gone - read `mc.options.fullscreen().get()`, toggle with
`mc.options.fullscreen().set(!mc.options.fullscreen().get())` (it calls `Window.setFullscreen`, applied by
`updateFullscreenIfChanged()` on the render thread). Private `Window.setMode()` still exists (WindowAccessor OK).
`MonitorManager`/`Monitor` replace `ScreenManager`.

## Swings, attacks, items

| 26.1.2 | 26.3 | Notes |
|---|---|---|
| `ServerboundSwingPacket(hand)` | gone; attacks/digs send `ServerboundPunchPacket.INSTANCE` (main hand, also resets the server's attack-strength ticker); uses/places swing server-side from the use packet | `SwingUtil` |
| `player.swing(hand)` | `player.swing(hand, SwingAnimation, sendToSwingingEntity)` - purely local on the client | `SwingUtil.use(hand)` / `SwingUtil.punch()` |
| `LivingEntity.swinging` | `isSwinging()` | also `getCurrentSwing()`, `getSwingAnimation(pt)` |
| `LivingEntity.getCurrentSwingDuration()` | `private getModifiedSwingDuration(SwingAnimation)` (read once when a swing starts) | SwingSpeedMixin |
| `gameMode.piercingAttack(weapon)` | `piercingAttack(stack.getAttackAnimation(), weapon)` - it swings itself now | |
| `localPlayer.drop(all)` | `mc.gameMode.dropItem(mc.player, all)` | AntiDropMixin now targets `MultiPlayerGameMode.dropItem` |
| `InteractionResult.SwingSource.CLIENT / SERVER` | `PREDICTED / SERVER_ONLY` | |
| `new ServerboundAcceptTeleportationPacket(id)` | `new ServerboundAcceptTeleportationPacket(id, x, y, z, yRot, xRot)` | vanilla no longer sends a PosRot after it |
| `GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, () -> false)` | `bind(decorator, new GameProtocols.Context() { hasInfiniteMaterials(); canUseCommandBlocks(); })` | |
| `ServerboundUseItemOnPacket.getHitResult()` | `hitResult()` (record) | |
| `ClientboundLevelChunkWithLightPacket.getX()/getZ()` | `x()/z()` (record) | |
| `ServerboundChatPacket(..., MessageSignature, ...)` | `Optional<MessageSignature>` | |
| `BlockPos.getCenter()` | `Vec3.atCenterOf(pos)` | `AABB.getCenter()` still exists |
| `BlockState.blocksMotion()` | `BlockUtil.blocksMotion(state)` | |
| `InterpolationHandler.position()` | `EntityUtil.interpolationTarget(e)` (`target()` is null when nothing is pending) | |
| `SignBlockEntity.getFrontText()` | `getText(SignTextSlot.FRONT)`; `SignText.getMessage(i, f)` -> `getMessages(f).get(i)` | |
| `PoseStack.mulPose(Quaternionf)` | `PoseStack.rotate(Quaternionfc)` (`mulPose` now takes `Matrix4fc`/`Transformation`) | |
| `mc.level.potionBrewing()` | gone: brewing is data-driven `BrewingRecipe`s; the client only has `recipeAccess().propertySet(RecipePropertySet.BREWING_REAGENTS)` and `PotionIngredient.isPotionInput(stack, recipeAccess)` | AutoBrewer (Gameplay) still needs this |

## Registries, renamed classes

| 26.1.2 | 26.3 | Notes |
|---|---|---|
| `EntityType.ZOMBIE` (constants) | `EntityTypes.ZOMBIE` (`net.minecraft.world.entity.EntityTypes`) | the `EntityType<T>` class itself is unchanged |
| `EnderMan` | `net.minecraft.world.entity.monster.Enderman` | |
| `BedItem` (`instanceof`) | `stack.is(ItemTags.BEDS)` | class removed |
| `AxeItem` (`instanceof`) | `stack.is(ItemTags.AXES)` | class removed |
| `Blocks.WHITE_CONCRETE`, `Items.WHITE_BED`, `Blocks.RED_WOOL`... | `Blocks.CONCRETE.pick(DyeColor.WHITE)`, `Items.BED.pick(DyeColor.WHITE)` | `ColorCollection<T>`: `BED, WOOL, CARPET, CONCRETE, CONCRETE_POWDER, STAINED_GLASS(_PANE), BANNER, DYE`; terracotta -> `DYED_TERRACOTTA`, shulker box -> `DYED_SHULKER_BOX`, candle -> `DYED_CANDLE`, bundle -> `DYED_BUNDLE`. `asList()`, `forEach`, `map`. |
| `BlockTags.DIAMOND_ORES` (and coal/lapis/redstone/emerald ores), `ItemTags.BUTTONS` | `BlockItemTags.DIAMOND_ORES.block()`, `BlockItemTags.BUTTONS.item()` | shared block+item tags live in `net.minecraft.tags.BlockItemTags` |
| `WorldPresets::createFlatWorldDimensions` | `r -> r.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions()` | |
| `VanillaRegistries.createLookup()` | `VanillaRegistries.createWorldLookup()` | what vanilla feeds `Commands.createValidationContext` |
| colour `EnvironmentAttributes` (`SKY_COLOR`, `FOG_COLOR`, `WATER_FOG_COLOR`, ...) `Integer` | `Vector3fc`; `CLOUD_COLOR`, `SUNRISE_SUNSET_COLOR` `Vector4fc` | `ARGB.vector3fFromRGB24/vector4fFromARGB32/colorFromVector3f`. Ambience fixed (returning an Integer there would ClassCastException at runtime). |
| `ChunkGeneratorStructureState(RandomState, BiomeSource, long, long, List)` | `(RandomState, BiomeSource, long seed, ChunkPos origin, long ringsSeed, List)` | access widener updated; StructurePredictor (Gameplay) must pass the origin |

## Renderer (renderpearl) pointers for the Render/Window agents

Not ported by Core, found while checking mixins:

| 26.1.2 | 26.3 |
|---|---|
| `com.mojang.blaze3d.pipeline.RenderPipeline/BlendFunction/DepthStencilState/ColorTargetState`, `platform.CompareOp` | `com.mojang.renderpearl.api.pipeline.*` |
| `com.mojang.blaze3d.buffers.GpuBuffer/GpuBufferSlice` | `com.mojang.renderpearl.api.buffers.*` |
| `com.mojang.blaze3d.vertex.VertexFormat/VertexFormatElement` | `com.mojang.renderpearl.api.vertex.*` |
| `com.mojang.blaze3d.textures.GpuTexture/GpuTextureView` | `com.mojang.renderpearl.api.textures.*` |
| `com.mojang.blaze3d.opengl.GlDevice.presentFrame()` | `com.mojang.renderpearl.backend.opengl.GlSurface.present()` (GL backend only; Vulkan backend exists too) |
| `com.mojang.blaze3d.opengl.GlDebug` | `com.mojang.renderpearl.backend.opengl.GlDebug` (same `printDebugLog` signature) |
| `MultiBufferSource` | gone; everything submits through `SubmitNodeCollector` |
| `SubmitNodeCollector.submitModel(model, state, pose, type, light, overlay, tint, TextureAtlasSprite, outline, CrumblingOverlay)` | `submitModel(model, state, pose, type, light, overlay, tint, UvMapping, outline)` (declared on `OrderedSubmitNodeCollector`) |
| `ItemInHandRenderer` | `net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer` (+ `state.level.FirstPersonHandsAndItemsRenderState`, per-player `LocalPlayer.firstPersonHandsAndItems()`) |
| `ScreenEffectRenderer.renderFire/renderWater/renderTex` | `submitFire/submitWater/submitBlockSprite` (submit-based) |
| `LevelRenderer.extractVisibleEntities/extractEntity` | `net.minecraft.client.renderer.extract.LevelExtractor` (same signatures; `mc.levelExtractor`) |
| `LevelRenderer.destroyingBlocks` | `ClientLevel.destroyingBlocks` (`LevelRendererAccessor` now targets ClientLevel: cast `mc.level`) |
| `GuiRenderer$Draw.indexCount` | `Draw.draw` is a `StagedVertexBuffer.Draw`; count via `stagedBuffer.getExecuteInfo(draw).indexCount()`. `GuiDrawAccessor.prism$draw()` replaces `prism$indexCount()`. |
| `GuiRenderState.strata` `List<Object>` | `List<GuiRenderState.Node>` (same erasure, shadow still matches) |

## Mixins verified by Core (targets, descriptors, @At against 26.3)

Unchanged and verified: AmbienceMixin, CameraMixin, CameraEffectMixin, ChatScreenMixin, ClientConnectionMixin,
ClientPlayNetworkHandlerMixin, ClientPlayerEntityMixin, ClientRecipeBookAccessor, CommandSuggestionsMixin (all shadows),
ContainerScreenAccessor, ContainerScreenMixin, EntityControlMixin, EntityControlStickMixin, EntityMixin,
EntityRendererMixin, FireworkRocketEntityMixin, FluidBlockMixin, FurnaceMenuInvoker, GuiRenderStateMixin,
InteractionManagerMixin, ItemTransformMixin, KeyboardInputMixin, LivingEntityMixin, LivingEntityRenderStateMixin,
LivingEntityRendererMixin, LocalPlayerTweaksMixin, MinecraftClientMixin, MobEffectFogMixin, NoRotateMixin,
PlayerEntityMixin, PotionSpoofMixin, RenderTickCounterMixin, RidingJumpMixin, SignEditAccessor, SlippyMixin,
SlotHighlightMixin, WindowAccessor.

Changed: AntiDropMixin (-> `MultiPlayerGameMode.dropItem`), ChamsMixin (new `submitModel` descriptor), CrosshairMixin +
InGameHudMixin (-> `Hud`), GlDebugTraceMixin + GlDeviceMixin (-> renderpearl GL backend), GuiDrawAccessor,
KeyboardMixin + MouseMixin (SDL codes, 5-arg `onMove`), LevelRendererAccessor (-> `ClientLevel`), LevelRendererMixin +
PopChamsMixin (-> `LevelExtractor`), SwingSpeedMixin (-> `getModifiedSwingDuration`).

Left to their owners: WindowMixin, DevHiddenWindowMixin, GuiRendererMixin, GameRendererMixin,
InGameOverlayRendererMixin, ItemInHandRendererMixin, HandViewMixin, SwordBlockMixin.
