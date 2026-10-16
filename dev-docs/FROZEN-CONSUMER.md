# Frozen 0.11.0 Java consumer

[`Catalog011Consumer.class.base64`](../ducklake-catalog/test/resources/compat/Catalog011Consumer.class.base64)
is Java 17 bytecode compiled from the [fixture source](../ducklake-catalog/test/resources/compat/Catalog011Consumer.java)
**against the Maven Central 0.11.0 JAR**, not the working project's classes.
The test loads it against current classes, exercising old constructor/accessor
descriptors. Recompiling it against a changed library would defeat that test.

Release JAR SHA-256:
`e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`.

Reproduction (use a JDK, no repository dependency is added):

```sh
curl --fail -o /tmp/opencode/catalog-011-release.jar https://repo.maven.apache.org/maven2/dev/brikk/ducklake/ducklake-catalog/0.11.0/ducklake-catalog-0.11.0.jar
sha256sum /tmp/opencode/catalog-011-release.jar # must match above
javac --release 17 -cp /tmp/opencode/catalog-011-release.jar -d /tmp/opencode/catalog-011-consumer ducklake-catalog/test/resources/compat/Catalog011Consumer.java
base64 -w 100 /tmp/opencode/catalog-011-consumer/dev/brikk/ducklake/catalog/compat/Catalog011Consumer.class
```

Missing annotation classes can produce harmless javac warnings in this minimal
compile; no annotation processing runs. This fixture locks selected descriptors,
not the entire library ABI. Existing airlift JSON goldens remain authoritative.
