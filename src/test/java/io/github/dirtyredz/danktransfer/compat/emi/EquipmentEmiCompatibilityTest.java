package io.github.dirtyredz.danktransfer.compat.emi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentEmiCompatibilityTest {
    @Test
    void acceptsOnlyTheKnownFullyQualifiedEquipmentHandler() {
        assertTrue(EquipmentEmiCompatibility.named("io.github.dirtyredz.evileyeclient.integration.emi.EquipmentEmiPlugin$Handler"));
        assertFalse(EquipmentEmiCompatibility.named("Handler"));
        assertFalse(EquipmentEmiCompatibility.named("other.EquipmentEmiPlugin$Handler"));
        assertFalse(EquipmentEmiCompatibility.named("io.github.dirtyredz.evileyeclient.integration.emi.CarriedRecipeFillHandler"));
    }
}
