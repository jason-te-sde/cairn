/**
 * The test suite, as a library.
 *
 * <p>Shipped as main code rather than test code for two reasons. It can be pointed at somebody
 * else's implementation of the rules — everything in here works against
 * {@link io.cairn.core.RegistryKernel} and {@link io.cairn.core.RegistryView} rather than against
 * the concrete kernel — and the deliberately broken implementations it contains need to be
 * compiled by the same {@code -Werror} build as everything else, because a flaw that no longer
 * compiles is a flaw that silently stops proving anything.
 *
 * <table>
 *   <caption>What is here</caption>
 *   <tr><th>Class</th><th>What it is for</th></tr>
 *   <tr><td>{@link io.cairn.testkit.Invariants}</td>
 *       <td>the twelve properties, as executable checks</td></tr>
 *   <tr><td>{@link io.cairn.testkit.Simulator}</td>
 *       <td>a whole registry, its replicas, its disks and its consumers, from one seed</td></tr>
 *   <tr><td>{@link io.cairn.testkit.CommandGenerator}</td>
 *       <td>a command stream shaped to reach the states that are hard to reason about</td></tr>
 *   <tr><td>{@link io.cairn.testkit.ReferenceKernel}</td>
 *       <td>a second implementation of the rules, to differ against</td></tr>
 *   <tr><td>{@link io.cairn.testkit.DifferentialRunner}</td>
 *       <td>drives two implementations through the same commands</td></tr>
 *   <tr><td>{@link io.cairn.testkit.Flaw}</td>
 *       <td>the known mistakes, and which check is supposed to catch each</td></tr>
 *   <tr><td>{@link io.cairn.testkit.FlawedComponents}</td>
 *       <td>broken stores and sinks, three of them faithful to a real system's defects</td></tr>
 *   <tr><td>{@link io.cairn.testkit.ColonCodec}</td>
 *       <td>the delimiter-joined record format this project replaces, runnable</td></tr>
 * </table>
 */
package io.cairn.testkit;
