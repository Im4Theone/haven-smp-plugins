package net.kaloyan.smpmarket.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * The exact item stack of a listing, plus the human-readable fields needed to display it without
 * deserializing it.
 *
 * @param payload     {@code ItemStack#serializeAsBytes()} output: the complete stack with all data components;
 *                    defensively copied
 * @param material    namespaced material key such as {@code minecraft:diamond_sword}
 * @param amount      stack size
 * @param description short plain-text summary (custom name, enchantments); may be empty
 */
public record ItemSnapshot(byte[] payload, String material, int amount, String description) {

    public ItemSnapshot {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(description, "description");
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemSnapshot that && amount == that.amount && material.equals(that.material)
                && description.equals(that.description) && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(material, amount, description) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return amount + "x " + material;
    }

    /**
     * Short display text such as {@code 3x diamond_sword (Sharpness V)}.
     *
     * @return the text
     */
    public String display() {
        String name = material.startsWith("minecraft:") ? material.substring("minecraft:".length()) : material;
        return amount + "x " + name + (description.isEmpty() ? "" : " (" + description + ")");
    }
}
