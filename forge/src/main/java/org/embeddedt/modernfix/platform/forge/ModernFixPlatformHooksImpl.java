package org.embeddedt.modernfix.platform.forge;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.Resources;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import cpw.mods.modlauncher.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.embeddedt.modernfix.core.ModernFixMixinPlugin;
import org.embeddedt.modernfix.api.constants.IntegrationConstants;
import org.embeddedt.modernfix.forge.classloading.FastAccessTransformerList;
import org.embeddedt.modernfix.forge.config.NightConfigFixer;
import org.embeddedt.modernfix.forge.config.NightConfigWatchThrottler;
import org.embeddedt.modernfix.forge.init.ModernFixForge;
import org.embeddedt.modernfix.forge.packet.PacketHandler;
import org.embeddedt.modernfix.forge.spark.SparkLaunchProfiler;
import org.embeddedt.modernfix.platform.ModernFixPlatformHooks;
import org.embeddedt.modernfix.util.CommonModUtil;
import org.embeddedt.modernfix.util.DummyList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.struct.InjectorGroupInfo;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class ModernFixPlatformHooksImpl implements ModernFixPlatformHooks {
    public boolean isClient() {
        return FMLLoader.getDist() == Dist.CLIENT;
    }

    public boolean isDedicatedServer() {
        return FMLLoader.getDist().isDedicatedServer();
    }

    private static final String verString = Optional.ofNullable(
    ModernFixMixinPlugin.class.getPackage().getImplementationVersion())
    .orElse("[unknown]");

    public String getVersionString() {
        return verString;
    }

    public boolean modPresent(String modId) {
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    public boolean isDevEnv() {
        return !FMLLoader.isProduction();
    }

    public MinecraftServer getCurrentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    public boolean isEarlyLoadingNormally() {
        return LoadingModList.get().getErrors().isEmpty();
    }

    public boolean isLoadingNormally() {
        return isEarlyLoadingNormally();
    }


    public TextureAtlasSprite loadTextureAtlasSprite(TextureAtlas atlasTexture,
                                                            ResourceManager resourceManager, TextureAtlasSprite.Info textureInfo,
                                                            Resource resource,
                                                            int atlasWidth, int atlasHeight,
                                                            int spriteX, int spriteY, int mipmapLevel,
                                                            NativeImage image) {
        TextureAtlasSprite tas = null; // ForgeHooksClient.loadTextureAtlasSprite(atlasTexture, resourceManager, textureInfo, resource, atlasWidth, atlasHeight, spriteX, spriteY, mipmapLevel, image);
        if(tas == null) {
            tas = TASConstructor.construct(atlasTexture, resourceManager, textureInfo, resource, atlasWidth, atlasHeight, spriteX, spriteY, mipmapLevel, image);
        }
        return tas;
    }

    static class TASConstructor {
        private static final MethodHandle textureAtlasSpriteConstruct;
        static {
            try {
                Constructor<?> constructor = TextureAtlasSprite.class.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                textureAtlasSpriteConstruct = MethodHandles.lookup().unreflectConstructor(constructor);
            } catch(IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        static TextureAtlasSprite construct(TextureAtlas atlasTexture,
                                            ResourceManager resourceManager, TextureAtlasSprite.Info textureInfo,
                                            Resource resource,
                                            int atlasWidth, int atlasHeight,
                                            int spriteX, int spriteY, int mipmapLevel,
                                            NativeImage image) {
            try {
                return (TextureAtlasSprite)textureAtlasSpriteConstruct.invokeExact(atlasTexture, textureInfo, mipmapLevel, atlasWidth, atlasHeight, spriteX, spriteY, image);
            } catch(Throwable e) {
                if(e instanceof RuntimeException)
                    throw (RuntimeException)e;
                else
                    throw new RuntimeException("TextureAtlasSprite construction failed", e);
            }
        }
    }

    public Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public void sendPacket(ServerPlayer player, Object packet) {
        PacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public void injectPlatformSpecificHacks() {
        /* We abuse the constructor of a mixin plugin as a safe location to start modifying the classloader */

        FastAccessTransformerList.attemptReplace();

        /* https://github.com/FabricMC/Mixin/pull/99 */
        try {
            Field groupMembersField = InjectorGroupInfo.class.getDeclaredField("members");
            groupMembersField.setAccessible(true);
            Field noGroupField = InjectorGroupInfo.Map.class.getDeclaredField("NO_GROUP");
            noGroupField.setAccessible(true);
            InjectorGroupInfo noGroup = (InjectorGroupInfo)noGroupField.get(null);
            groupMembersField.set(noGroup, new DummyList<>());
        } catch(NoSuchFieldException ignored) {
            // Connector will replace FML's mixin with one which already has the fix, don't bother logging
        } catch(RuntimeException | ReflectiveOperationException e) {
            ModernFixMixinPlugin.instance.logger.error("Failed to patch mixin memory leak", e);
        }

        if(ModernFixMixinPlugin.instance.isOptionEnabled("feature.spark_profile_launch.OnForge")) {
            CommonModUtil.runWithoutCrash(() -> SparkLaunchProfiler.start("launch"), "Failed to start profiler");
        }

        NightConfigFixer.monitorFileWatcher();
        NightConfigWatchThrottler.throttle();
        MixinExtrasBootstrap.init();
    }

    private Method defineClassMethod = null;

    private Class<?> injectClassIntoSystemLoader(String className) throws ReflectiveOperationException, IOException {
        ClassLoader systemLoader = ClassTransformer.class.getClassLoader();
        if(defineClassMethod == null) {
            defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClassMethod.setAccessible(true);
        }
        byte[] newTransformerBytes = Resources.toByteArray(ModernFixMixinPlugin.class.getResource("/" + className.replace('.', '/') + ".class"));
        return (Class<?>)defineClassMethod.invoke(systemLoader, className, newTransformerBytes, 0, newTransformerBytes.length);
    }

    public void applyASMTransformers(String mixinClassName, ClassNode targetClass) {
        if(mixinClassName.equals("org.embeddedt.modernfix.forge.mixin.bugfix.chunk_deadlock.valhesia.BlockStateBaseMixin")) {
            // We need to destroy Valhelsia's callback so it can never run getBlockState
            for(MethodNode m : targetClass.methods) {
                if(m.name.contains("valhelsia_placeDousedTorch")) {
                    m.instructions.clear();
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                }
            }
        }
    }

    public void onServerCommandRegister(Consumer<CommandDispatcher<CommandSourceStack>> handler) {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLServerStartingEvent event) -> {
            handler.accept(event.getCommandDispatcher());
        });
    }

    private static Multimap<String, String> modOptions;
    public Multimap<String, String> getCustomModOptions() {
        if(modOptions == null) {
            modOptions = ArrayListMultimap.create();
            for (ModInfo meta : LoadingModList.get().getMods()) {
                meta.getConfigElement(IntegrationConstants.INTEGRATIONS_KEY).ifPresent(optionsObj -> {
                    if(optionsObj instanceof Map) {
                        Map<Object, Object> options = (Map<Object, Object>)optionsObj;
                        options.forEach((key, value) -> {
                            if(key instanceof String && value instanceof String) {
                                modOptions.put((String)key, (String)value);
                            }
                        });
                    }
                });
            }
        }
        return modOptions;
    }

    public void onLaunchComplete() {
        if(ModernFixMixinPlugin.instance.isOptionEnabled("feature.spark_profile_launch.OnForge")) {
            CommonModUtil.runWithoutCrash(() -> SparkLaunchProfiler.stop("launch"), "Failed to stop profiler");
        }
        ModernFixForge.launchDone = true;
    }

    public String getPlatformName() {
        return "Forge";
    }
}
