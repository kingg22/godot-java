# Modelo de Ownership: Kotlin/Native GC vs Godot RefCounted

Ver [issue #114](https://github.com/kingg22/kogot/issues/114).

## Problema confirmado (no solo teórico)

Con el binding tal como estaba antes de este cambio, se verificaron empíricamente dos bugs contra el
binario dev de Godot (`godot-version` v4.7.1, `mi-juego-prueba/kotlin_native_game`):

1. **Identidad rota**: `castTo`/`GD.load` construían una instancia Kotlin nueva en cada llamada para el
   mismo puntero nativo (`sameIdentity=false` en la prueba, dos `GD.load("res://icon.svg")` seguidos
   devolvían wrappers Kotlin distintos para el mismo `Texture2D`). Para una clase de usuario con estado
   mutable, esto significa perder ese estado silenciosamente cada vez que el mismo objeto vuelve a
   pasar por un `castTo`.
2. **Fuga de referencia por cada `GD.load`/lectura de propiedad**: cada retorno de método/lectura de
   propiedad que entrega un `RefCounted` transfiere una referencia +1 que nadie liberaba nunca
   (confirmado viendo `get_reference_count()` subir de 1 a 9 sin nunca bajar, en una prueba trivial).
3. **Double-dispose real en `freeInstanceFunc`**: `p_class_userdata` (el `StableRef<KClass>` *por
   clase*, compartido por todas las instancias) se disponía en el free de **cada instancia**. La
   segunda instancia de una misma clase (`Sprite`, `SpriteBench`, ...) que se liberara ya estaba
   re-disponiendo un `StableRef` muerto — undefined behavior garantizado en cualquier proyecto con más
   de una instancia viva de una clase que llega a liberar dos.

## Lo que se arregló en esta fase

Un registro único de identidad (`kotlin-native/binding/.../internal/binding/ObjectIdentity.kt`) sobre
`object_get_instance_binding`, con `create_callback`/`free_callback` reales (antes los tres callbacks
eran `null` en todos los call sites):

- **Identidad**: `castTo`/`castToOrNull`/`GD.load`/`instantiate<T>()` resuelven todos el mismo slot;
  nunca se fabrica una segunda instancia Kotlin para el mismo puntero nativo. La referencia guardada es
  `WeakReference` — para una clase de usuario el StableRef real ya lo sostiene `object_set_instance`
  (obligatorio para ClassDB), así que el mirror puede ser débil sin riesgo; para un wrapper puramente de
  motor (sin `create_instance_func`) es simplemente lo correcto: si nada en Kotlin lo referencia, se
  puede reconstruir en el próximo acceso.
- **Double-dispose**: `freeInstanceFunc` ya no toca `p_class_userdata`, solo el `StableRef` de la
  instancia.

Verificado corriendo `mi-juego-prueba` contra el binario dev real: misma identidad en cargas repetidas,
y las 5 instancias de `Sprite` (todas comparten `class_userdata`) se liberan sin crash.

## Lo que se dejó explícitamente sin resolver

La fuga de referencia (punto 2) **no** se resolvió en esta fase. Se intentó liberar la referencia
implícita de un wrapper `RefCounted` materializado (no creado por nosotros) con un
`kotlin.native.ref.Cleaner`, ejecutando `unreference()` (+ `object_destroy` si correspondía) cuando el
wrapper se vuelve inalcanzable desde Kotlin. **Esto crasheó de forma reproducible**:

```
ERROR: The caller thread can't call the function `propagate_notification()` on this node.
Use `call_deferred()` or `call_deferred_thread_group()` instead.
handle_crash: Program crashed with signal 11
```

Los `Cleaner` de Kotlin/Native corren en un thread finalizador dedicado, separado del thread principal
donde vive Godot. Las llamadas al motor no son en general thread-safe fuera del hilo principal — esto
se disparó con un `Resource` compartido cuyo wrapper se recolectó mientras el hilo principal todavía lo
usaba.

**Camino correcto (no implementado aquí)**: diferir la liberación al hilo principal, p. ej. agendando un
`Callable.callDeferred()` desde el `Cleaner` en vez de llamar directo al motor. `Object.call_deferred`
empuja a `MessageQueue`, que sí es thread-safe para encolar desde cualquier hilo (mutex-protegido); lo
que no es seguro es invocar la lógica de motor *directamente* fuera del hilo principal. Esto es
justamente "threading safety de callbacks nativos", ítem separado del roadmap de Fase 2 — no se mezcló
con este fix de identidad para no arriesgar otra regresión sin poder validarla contra el binario real en
esta misma pasada.

## Referencia: cómo lo resuelve Godot mismo

`modules/mono/csharp_script.cpp` (`CSharpLanguage::_instance_binding_reference_callback`) implementa
exactamente este problema para un lenguaje con GC de trazado: alterna el `GCHandle` del wrapper entre
`weak` y `strong` según el refcount nativo, usando `reference_callback` (que este fix todavía no usa —
solo hace falta para ese caso de ownership completo, no para la identidad). Es la referencia a seguir
para la Fase 2.
