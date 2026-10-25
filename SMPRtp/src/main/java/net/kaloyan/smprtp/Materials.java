package net.kaloyan.smprtp;

import java.util.Set;
import net.kaloyan.smprtp.SpotFinder.Classifier;
import org.bukkit.Material;
import org.bukkit.Tag;

/** The real meaning of materials on a running server. */
public final class Materials implements Classifier {

    private static final Set<Material> UNSAFE_GROUND = Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.POWDER_SNOW, Material.TNT, Material.COBWEB,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.CHORUS_PLANT, Material.CHORUS_FLOWER,
            Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.BEDROCK, Material.SPAWNER, Material.TRIAL_SPAWNER,
            Material.WITHER_ROSE, Material.SWEET_BERRY_BUSH);

    private static final Set<Material> FREE_PLANTS = Set.of(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW);

    private static final Set<Material> HAZARDS = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE);

    @Override
    public boolean standable(Material material) {
        return material.isSolid() && !UNSAFE_GROUND.contains(material) && !Tag.LEAVES.isTagged(material)
                && !Tag.TRAPDOORS.isTagged(material) && !Tag.SLABS.isTagged(material) && !Tag.STAIRS.isTagged(material);
    }

    @Override
    public boolean free(Material material) {
        return material.isAir() || FREE_PLANTS.contains(material);
    }

    @Override
    public boolean hazard(Material material) {
        return HAZARDS.contains(material);
    }
}
