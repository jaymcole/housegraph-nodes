import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What a node library declares about itself. The manifest baked into its jar is generated from
 * this, so nothing here can drift from what is actually released.
 *
 * <p>Everything else — the shaded jar, the JavaFX setup, the compileOnly API dependency, the asset
 * naming HouseGraph matches on — is handled by the {@code housegraph-node-library} convention
 * plugin, so a subproject only has to say who it is.
 */
abstract class NodeLibraryExtension {

    /**
     * The library id. Used as the manifest id, the install directory name, the key recorded in
     * save files, and the jar's base name. Must be lowercase {@code [a-z0-9][a-z0-9._-]*} — it has
     * to survive being a path segment. Never change it after a release: save files reference it.
     */
    abstract Property<String> getId()

    /** Human label shown in HouseGraph's library window. Defaults to the id. */
    abstract Property<String> getLibraryName()

    /** One line shown in the install confirmation. */
    abstract Property<String> getDescription()

    /** Packages HouseGraph scans for node classes. A node outside these is invisible. */
    abstract ListProperty<String> getNodePackages()

    /**
     * The Add-Node submenu this library's nodes nest under. Defaults to the id with any
     * {@code housegraph-} prefix stripped, so {@code housegraph-squirrel} groups its nodes under "iot" —
     * which is what they were called while they lived in the app.
     */
    abstract Property<String> getCategoryPrefix()
}
