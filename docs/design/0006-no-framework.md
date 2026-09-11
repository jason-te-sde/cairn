# 6. No web framework, and no JSON library

`cairn-server` uses the JDK's own `com.sun.net.httpserver`, a fixed thread pool, and a JSON codec
written for this API. The entire runtime dependency list for the project is SLF4J.

## Why

**The API is small.** Fourteen routes and about fifteen fields. A framework's value is in what it
does for a surface much larger than that: content negotiation, validation annotations, dependency
injection, filters. None of it is load-bearing here, and all of it would be on the compile path.

**A strict parser is worth more than a permissive one.** This is the real argument, and it is not
about size. A general-purpose JSON parser is built to accept what people send; the one here is built
to refuse anything it did not expect. It rejects trailing commas, comments, unquoted keys, single
quotes, `NaN`, leading zeros, unescaped control characters inside strings, nesting past a depth
limit, documents past a size limit, floating-point numbers, and **duplicate keys**.

That last one is not pedantry. Duplicate keys are legal JSON, and two parsers can disagree about
which value wins — which is how one request means one thing to a proxy and something else to the
service behind it. Most libraries resolve it silently; this one refuses the document. For a service
whose entire argument is that it does not trust its input, that is the right default, and it is not
a default any library offers.

Floating-point is refused for a domain reason: this API has no floating-point field, and accepting
one would raise the question of what a version of `2.0` means.

**A clean clone builds in seconds** and the container image is a jar plus a JRE. That is not the
reason, but it is a pleasant consequence, and it makes the two-JDK CI matrix cheap enough to run on
every commit.

## Costs

**About 550 lines to maintain**, between `Json` and `Api`, that a dependency would have provided.
Paid for by `JsonTest`, which is 38 tests and mostly a rejection corpus — the file is the
justification for having written the parser, since a general-purpose one accepts most of what is in
it.

**No OpenAPI generation, no content negotiation, no HTTP/2.** The routes are documented in the
`Api` class header and in the README. A client is `curl`.

**`com.sun.net.httpserver` is not a high-performance server.** It is a thread-per-request model with
no connection multiplexing. For a registry — whose write path is one command at a time through a
single owning thread, and whose read path is a lock-free reference read — the HTTP layer is not the
bottleneck. If it ever were, the fix is to put a real server in front, and nothing above the
transport would change.

**Hand-rolled logging was considered and rejected.** SLF4J is a facade, not an implementation, and a
library that picks a logging backend for its embedder is a library that fights with their existing
one. The executable jar ships `slf4j-simple` at runtime scope only, because a server with no binding
logs nothing at all.

## Rejected alternatives

**Spring Boot.** Would have given actuator endpoints, validation and an OpenAPI document for free,
and brought roughly a hundred jars, a second configuration system, and a startup time measured in
seconds. The two things worth having — metrics and readiness — are forty lines each here, and the
metrics that matter are domain metrics (`cairn_outbox_depth`) that no framework would have supplied.

**gRPC.** The author's previous project uses it, and it is the right choice when the payload is a
schema and the callers are services. Here the payload is a blob and the callers are a CLI, a CI job
and `curl`; HTTP with a digest in a header is the interface those already speak.

**Jackson, for the fifteen fields.** One dependency, well maintained, and permissive by design.
Configuring it into strictness is possible and is a list of settings somebody has to keep enabled;
being strict by construction is a property that cannot be switched off by accident.
