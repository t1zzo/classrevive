# Zelix KlassMaster 27: static reversal

`--passes zkm`, and automatically in `decompile` when the diagnosis names `Zelix KlassMaster 27` with HIGH confidence, reverses the protections of Zelix KlassMaster 27. ClassRevive never runs the analyzed code: the obfuscator's initializers and lookups are interpreted, with `Class.forName` and the member finders answered by symbolic references, so no class is loaded.

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

## What stays, and why

- A protected method nothing in the jar calls has no known key, so its reflective accesses cannot be resolved. It keeps its `Object[]` signature and declares `throws Exception`, since those accesses throw checked exceptions the obfuscator let through. The generated reflection class stays while such a method needs it.
- A packed method whose unpacked parameters would collide with another method of the hierarchy stays packed.
- A flag is folded only under a proof. A reflective write that could reach a static flag cancels folding for the whole jar.

Every rewritten method is checked by the JDK's verifier analysis, and the JVM verifier links the result.

## Measurement

Two samples protected with Zelix KlassMaster 27 (16 classes each, different name dictionaries). Before this pass neither compiled, with 0/184 signatures matched. After it, both compile with 164/164 signatures; one has a constructor no engine structured, left as a placeholder. Six reflective accesses per sample remain, in methods nothing calls.

Signatures are measured against the reversed classes. They do not prove equivalence with the program before protection, and behavior is not verified. Other configurations of the obfuscator may use shapes the pass refuses; what it refuses stays as it was and is listed in its report.
