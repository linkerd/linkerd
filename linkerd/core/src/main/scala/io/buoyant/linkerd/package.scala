package io.buoyant
/**
 * Linkerd provides a modular & pluggable configuration layer to
 * support programmatic and configuration-driven initialization of
 * software routers.
 *
 * The library provides a family of configuration types:
 *
 * <pre>
 *      --------
 *     | Linker |
 *      --------
 *       |  --------
 *       |-| Router |
 *       |  --------
 *       |   |  --------
 *       |   |-| Server |
 *       |   |  --------
 *       |   `- ...
 *       `- ...
 * </pre>
 *
 *  * A [[io.buoyant.linkerd.Linker Linker]] represents the complete
 *    runtime configuration for a linkerd application (and not its
 *    virtual machine).  A linker must have one or more
 *    [[io.buoyant.linkerd.Router Routers]].
 *
 *  * A [[io.buoyant.linkerd.Router Router]] represents the complete
 *    runtime configuration for a router--the outbound client-side
 *    dispatching module--and its serving interfaces,
 *    [[io.buoyant.linkerd.Server Servers]].
 *
 * The [[io.buoyant.linkerd.ProtocolInitializer ProtocolInitializer]]
 * exposes a protocol-agnostic interface supporting protocol-aware
 * configuration and initialization. ProtocolInitializer modules are
 * discovered at runtime with finagle's `LoadService` facility.
 */
package object linkerd
