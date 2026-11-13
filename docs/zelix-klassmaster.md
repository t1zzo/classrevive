# Zelix KlassMaster 27: static reversal

`--passes zkm`, and automatically in `decompile` when the diagnosis names `Zelix KlassMaster 27` or `Zelix KlassMaster` with HIGH confidence, reverses the protections of Zelix KlassMaster 27. It covers both shapes the obfuscator writes: reflection for older class files, and `invokedynamic` for Java 7 and later targets (see below). ClassRevive never runs the analyzed code: the obfuscator's initializers and lookups are interpreted, with `Class.forName` and the member finders answered by symbolic references, so no class is loaded.

```bat
classrevive decompile protected.jar --output result
classrevive transform protected.jar --output reversed --passes zkm
```

## What is undone

| Protection | Shape | Reversal |
| --- | --- | --- |
| Method keys | a `long` local, class field, parameter (whole or split in ints), or `Object[]` element | constant propagation, with keys learned across the jar round after round |
| Encrypted strings and numbers | keyed decryptors over tables filled in `<clinit>`; `static final String` constants | evaluated at each call whose arguments are constants |
| Reflection | a generated class's `(long, long)` getters, then `invoke`, `get*`, `set*`, behind stack shuffles | the member is resolved and the direct instruction written back; boxing and `InvocationTargetException` unwrapping go |
| Packed parameters | `m(Object[] {args..., key})`, callers filling the array by stack shuffling | one parameter per element, typed by the method's reads |
| Key parameters | an element only used to derive keys | left out, by an optimistic fixed point over the whole jar |
| Opaque flags | a `static boolean` or `int` tested in front of real code | proved zero by a fixed point, then folded |
| Initializers | decrypting loops in `<clinit>`, JSR subroutines | removed; values still read are set as constants, tables as array literals |
| Leftovers | pure key expressions, jumps to the next instruction, handlers that only rethrow, key statements before `super()` | removed or moved |
| Exception obfuscation | `catch (RuntimeException e) { throw a(e); }`, `a` returning its argument | the call goes, then the handler that only rethrows, then the helper |

## Java 7 and later targets: `invokedynamic`

For a class file of Java 7 or later, Zelix KlassMaster 27 hides each encrypted string and number behind an `invokedynamic` site. The site's bootstrap, a static method of the class, builds a `MutableCallSite` whose target collects the arguments (an `int` and a `long` key) into a resolver. The resolver calls the class's decryptor and installs the result as a constant. ClassRevive recognizes that chain from the bytecode and treats each site as a call to the decryptor, so a site with constant arguments becomes its value.

| Part | Shape | Reversal |
| --- | --- | --- |
| Class key | `a = G.a(k1, k2, MethodHandles.lookup().lookupClass()).a(k3)`, `G` a key generator of a few jar classes (bit permutation tables, objects in lists and maps keyed by their own `hashCode` and `equals`) | the generator is interpreted; super classes are initialized first, as the JVM does, since the obfuscator chains a subclass's key to its super class's |
| String and number tables | DES (`DES/CBC/PKCS5Padding`) over the class key, decrypted lazily by the decryptor or in `<clinit>` | decrypted on constants with the JDK's own DES |
| Sites | `invokedynamic (IJ)String`, `(IJ)I`, `(IJ)J` | replaced by the value; the bootstrap, the resolver and the decryptor go once nothing uses them |

The interpreter (`ZkmRuntime`) runs jar code only for classes whose every instruction it supports, computed as a greatest fixed point over the jar; objects of jar classes are its own values, the JDK collections they use are modelled, and an identity hash code refuses. The key generator's initializer sorts and links a few thousand of its objects once per jar, on a budget of its own.

What stays:

- Classes whose `<clinit>` only hands the class to native code (a separate native protection that registers native methods and sets the keys and tables outside the bytecode). Their sites have no key in the bytecode and stay as they are; the report counts them as `classes whose initializer is native code`.
- A class whose key depends on another class initialized earlier by the program's own code, not by the class hierarchy. Its decryption fails the DES padding check and is refused; nothing is replaced on a guess.

## What stays, and why

- A protected method nothing in the jar calls has no known key, so its reflective accesses cannot be resolved. It keeps its `Object[]` signature and declares `throws Exception`, since those accesses throw checked exceptions the obfuscator let through. The generated reflection class stays while such a method needs it.
- A packed method whose unpacked parameters would collide with another method of the hierarchy stays packed.
- A flag is folded only under a proof. A reflective write that could reach a static flag cancels folding for the whole jar.

Every rewritten method is checked by the JDK's verifier analysis, and the JVM verifier links the result.

## Measurement

Two samples protected with Zelix KlassMaster 27 (16 classes each, different name dictionaries). Before this pass neither compiled, with 0/184 signatures matched. After it, both compile with 164/164 signatures; one has a constructor no engine structured, left as a placeholder. Six reflective accesses per sample remain, in methods nothing calls.

A real Java 8 jar protected with Zelix KlassMaster 27's `invokedynamic` shape (715 classes, 127 of them protected, an unprotected library bundled; 24 classes also carry a native protection whose initializers run outside the bytecode): 974 of 1,514 sites decrypted (587 strings, 387 numbers), 79 class keys recovered in 68 classes, 3,984 exception-obfuscation calls and 285 decryption methods removed, the bundled library left byte for byte. Of the 540 sites left, 499 are in the natively initialized classes. In the per-file round trip, the protected program's files that do not compile alone dropped from 123 to 85. The bundled library's rose from 46 to 114 with the same classes and the same engine outputs: the whole-tree compilation stops at javac's error limit, so selection picked other engines' files for the library, among them a `throws` clause JADX adds that the bytecode does not declare. That is a selection issue, left for its own change.

Signatures are measured against the reversed classes. They do not prove equivalence with the program before protection, and behavior is not verified. Other configurations of the obfuscator may use shapes the pass refuses; what it refuses stays as it was and is listed in its report.
