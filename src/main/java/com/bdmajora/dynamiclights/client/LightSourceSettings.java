package com.bdmajora.dynamiclights.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.bdmajora.dynamiclights.mixin.TileEntityRegistryAccessor;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entity and block entity types the user has switched off.
 *
 * <p>Upstream LambDynLights builds this list from the {@code EntityType} and {@code BlockEntityType}
 * registries. 1.12.2 has equivalents — Forge's entity registry and {@code TileEntity}'s class-to-id
 * map — so unlike the Extras particle toggles this needs no reflection or spawn-time discovery; the
 * registries are authoritative and complete by the time the options screen can be opened.
 *
 * <p>Only the disabled sets are user data. Everything else is derived from the registries on demand.
 *
 * <p>Celeritas Dynamic Lights carries the same option surface but never populates it: its settings
 * map is a fresh empty {@code HashMap} built inside the lookup, so every type reads as enabled and
 * the toggles do nothing. This is the working version of that.
 */
public final class LightSourceSettings {
    private static final LightSourceSettings INSTANCE = new LightSourceSettings();

    /** Registry ids the user has switched off. The only authoritative persisted state here. */
    private final Set<String> disabledEntities = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledBlockEntities = ConcurrentHashMap.newKeySet();

    /** Per-class memo of the registry id, so the hot tick path resolves it at most once per class. */
    private final Map<Class<?>, String> entityIds = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> blockEntityIds = new ConcurrentHashMap<>();

    /** Stands in for "this class has no registry id", which a {@link ConcurrentHashMap} cannot store. */
    private static final String UNREGISTERED = "";

    private LightSourceSettings() {
    }

    public static LightSourceSettings getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------------------------------
    // Lookups on the tick path
    // ------------------------------------------------------------------------------------------

    /** Whether this entity's type may light up. */
    public boolean isEntityEnabled(Entity entity) {
        if (this.disabledEntities.isEmpty()) {
            return true;
        }

        String id = this.entityId(entity);
        return id == null || !this.disabledEntities.contains(id);
    }

    /** Whether this block entity's type may light up. */
    public boolean isBlockEntityEnabled(TileEntity tileEntity) {
        if (this.disabledBlockEntities.isEmpty()) {
            return true;
        }

        String id = this.blockEntityId(tileEntity);
        return id == null || !this.disabledBlockEntities.contains(id);
    }

    /**
     * The registry id for this entity, or null if it has none.
     *
     * <p>Keyed on the concrete class rather than the instance because {@link EntityList#getKey} walks
     * a map lookup and this runs once per entity per tick. A modded subclass registered under its own
     * id resolves to that id; one that is not registered at all resolves to null and is therefore
     * never filtered — which is the safe direction to fail, since an unlistable type has no toggle.
     */
    private String entityId(Entity entity) {
        Class<?> clazz = entity.getClass();
        String cached = this.entityIds.get(clazz);

        if (cached == null) {
            ResourceLocation key = EntityList.getKey(entity);
            cached = key == null ? UNREGISTERED : key.toString();
            this.entityIds.put(clazz, cached);
        }

        return cached.isEmpty() ? null : cached;
    }

    private String blockEntityId(TileEntity tileEntity) {
        Class<?> clazz = tileEntity.getClass();
        String cached = this.blockEntityIds.get(clazz);

        if (cached == null) {
            ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
            cached = key == null ? UNREGISTERED : key.toString();
            this.blockEntityIds.put(clazz, cached);
        }

        return cached.isEmpty() ? null : cached;
    }

    // ------------------------------------------------------------------------------------------
    // The disabled sets
    // ------------------------------------------------------------------------------------------

    public boolean isEntityTypeDisabled(String id) {
        return this.disabledEntities.contains(id);
    }

    public boolean isBlockEntityTypeDisabled(String id) {
        return this.disabledBlockEntities.contains(id);
    }

    public void setEntityTypeEnabled(String id, boolean enabled) {
        if (enabled) {
            this.disabledEntities.remove(id);
        } else {
            this.disabledEntities.add(id);
        }
    }

    public void setBlockEntityTypeEnabled(String id, boolean enabled) {
        if (enabled) {
            this.disabledBlockEntities.remove(id);
        } else {
            this.disabledBlockEntities.add(id);
        }
    }

    public void loadDisabledEntities(String[] ids) {
        load(this.disabledEntities, ids);
    }

    public void loadDisabledBlockEntities(String[] ids) {
        load(this.disabledBlockEntities, ids);
    }

    public String[] getDisabledEntitiesArray() {
        return this.disabledEntities.stream().sorted().toArray(String[]::new);
    }

    public String[] getDisabledBlockEntitiesArray() {
        return this.disabledBlockEntities.stream().sorted().toArray(String[]::new);
    }

    private static void load(Set<String> target, String[] ids) {
        target.clear();
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                target.add(id);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Enumeration, for the options page
    // ------------------------------------------------------------------------------------------

    /**
     * Every registered entity type as {@code "namespace:path" -> display name}, sorted by id.
     *
     * <p>Built on demand rather than cached: the options page reads it once per open, and holding a
     * copy would only go stale against a registry that can change on world join.
     */
    public static Map<String, String> listEntityTypes() {
        Map<String, String> types = new TreeMap<>();

        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            ResourceLocation id = entry.getRegistryName();
            if (id != null) {
                types.put(id.toString(), displayName(entry.getName(), id));
            }
        }

        return types;
    }

    /**
     * Every registered block entity type as {@code "namespace:path" -> display name}, sorted by id.
     *
     * <p>Block entities are still vanilla-registered on 1.12.2 — there is no Forge registry to walk —
     * so this reaches {@code TileEntity.REGISTRY} through an accessor mixin.
     */
    public static Map<String, String> listBlockEntityTypes() {
        Map<String, String> types = new TreeMap<>();

        for (ResourceLocation id : TileEntityRegistryAccessor.impetus$getRegistry().getKeys()) {
            types.put(id.toString(), displayName(null, id));
        }

        return types;
    }

    /** A human-readable label: the registered name where there is one, else the id's path. */
    private static String displayName(String registeredName, ResourceLocation id) {
        if (registeredName != null && !registeredName.isEmpty()) {
            return registeredName;
        }
        return prettify(id.getPath());
    }

    /** {@code wall_banner -> Wall Banner}. */
    private static String prettify(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean capitalise = true;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/' || c == '.') {
                out.append(' ');
                capitalise = true;
            } else if (capitalise) {
                out.append(Character.toUpperCase(c));
                capitalise = false;
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }
}
