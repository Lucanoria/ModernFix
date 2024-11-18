package org.embeddedt.modernfix.common.mixin.perf.state_definition_construct;

import com.google.common.collect.ImmutableSortedMap;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.embeddedt.modernfix.annotation.RequiresMod;
import org.embeddedt.modernfix.blockstate.FakeStateMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

@Mixin(StateDefinition.class)
@RequiresMod("ferritecore")
public class StateDefinitionMixin<O, S extends StateHolder<S>> {
    @Shadow @Final private ImmutableSortedMap<String, Property<?>> propertiesByName;

    @ModifyVariable(method = "<init>", at = @At(value = "STORE", ordinal = 0), ordinal = 1, index = 8)
    private Map<Map<Property<?>, Comparable<?>>, S> useArrayMap(Map<Map<Property<?>, Comparable<?>>, S> in) {
        int numStates = 1;
        for(Property<?> prop : this.propertiesByName.values()) {
            numStates *= prop.getPossibleValues().size();
        }
        return new FakeStateMap<>(numStates);
    }
}
