package com.richardluo.mifocusconverter.utils

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.graphics.drawable.Drawable.ConstantState
import androidx.annotation.CheckResult
import androidx.collection.LruCache
import java.io.File
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser

@CheckResult
fun Int.withHighByte(flag: Int): Int {
  return this and 0x00ffffff or flag
}

fun Int.highByte(): Int {
  return this and 0xff000000.toInt()
}

fun Int.isHighByte(flag: Int): Boolean {
  return highByte() == flag
}

// Fix when classname is empty
fun unflattenFromString(str: String): ComponentName? {
  val sep = str.indexOf('/')
  if (sep < 0) {
    return null
  }
  val pkg = str.take(sep)
  var cls = str.substring(sep + 1)
  if (cls.isNotEmpty() && cls[0] == '.') {
    cls = pkg + cls
  }
  return ComponentName(pkg, cls)
}

infix fun String.rEqual(other: String): Boolean {
  if (length != other.length) return false
  for (i in other.indices) if (this[length - 1 - i] != other[length - 1 - i]) return false
  return true
}

infix fun String.rNEqual(other: String) = !rEqual(other)

fun <T> Array<T>.rGet(i: Int) = getOrNull(if (i >= 0) i else size + i)

fun <T> List<T>.rGet(i: Int) = getOrNull(if (i >= 0) i else size + i)

fun <T> Array<T>.rSet(i: Int, obj: T) {
  val index = if (i >= 0) i else size + i
  if (index in indices) this[index] = obj
}

inline fun <reified T> Any?.asType() = this as? T

inline fun Boolean.ifTrue(block: () -> Unit) = if (this) block() else this

inline fun Boolean.ifFalse(block: () -> Unit) = if (!this) block() else this

inline fun List<*>?.ifNullOrEmpty(block: () -> Unit) = if (isNullOrEmpty()) block() else this

inline fun <T> Result<T>.getOrNull(block: (Throwable) -> Unit) = getOrElse {
  block(it)
  null
}

inline fun <K, V> MutableMap<K, V?>.getOrPutNullable(key: K, defaultValue: () -> V?) =
    if (containsKey(key)) get(key) else defaultValue().also { put(key, it) }

/** Whether the current thread is handling a binder call from another process. */
fun isInBinderCall() = android.os.Binder.getCallingPid() != android.os.Process.myPid()

fun String.ifNotEmpty(block: (String) -> String) = if (isNotEmpty()) block(this) else this

operator fun XmlPullParser.get(key: String): String? = this.getAttributeValue(null, key)

suspend inline fun <K : Any, V : Any> LruCache<K, V>.getOrPut(
    key: K,
    taskMap: MutableMap<K, Deferred<V>>,
    defaultValue: () -> Deferred<V>,
): V {
  val value =
      synchronized(this) {
            val value = get(key)
            if (value != null) return value

            taskMap.getOrPut(key) { defaultValue() }
          }
          .await()

  synchronized(this) {
    val oldValue = get(key)
    if (oldValue == null) {
      put(key, value)
      taskMap.remove(key)
    }
  }
  return value
}

@OptIn(FlowPreview::class)
fun Flow<CharSequence>.debounceInput(delay: Long = 300L) = debounce {
  if (it.isEmpty()) 0L else delay
}

fun flowTrigger() =
    MutableSharedFlow<Unit>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
      tryEmit(Unit)
    }

suspend fun MutableSharedFlow<Unit>.emit() = emit(Unit)

fun MutableSharedFlow<Unit>.tryEmit() = tryEmit(Unit)

inline fun <R> runSafe(crossinline block: () -> R) = run { block() }

inline fun <T, R> T.runSafe(crossinline block: T.() -> R) = run { block() }

inline fun <T, reified R> Array<T>.map(transform: (T) -> R): Array<R> {
  return Array(size) { i -> transform(this[i]) }
}

inline fun <T, reified R> Array<T>.mapIndexed(transform: (Int, T) -> R): Array<R> {
  return Array(size) { i -> transform(i, this[i]) }
}

inline fun <K, reified V> MutableMap<K, V>.getOrPut(
    keys: List<K>,
    fetch: (List<K>) -> Array<V>,
): List<V?> {
  val (hits, misses) = keys.indices.partition { contains(keys[it]) }
  val array = arrayOfNulls<V>(keys.size)
  hits.forEach { array[it] = this[keys[it]] }
  if (misses.isNotEmpty())
      fetch(misses.map { keys[it] }).forEachIndexed { i, value ->
        val index = misses[i]
        this[keys[index]] = value
        array[index] = value
      }
  return array.asList()
}

inline fun <T> Flow<List<T>?>.filter(
    searchText: Flow<CharSequence>,
    crossinline predicate: (T, CharSequence) -> Boolean,
) =
    combineTransform(searchText.debounceInput()) { items, text ->
          emit(null)
          items ?: return@combineTransform
          emit(if (text.isEmpty()) items else items.filter { predicate(it, text) })
        }
        .conflate()
        .flowOn(Dispatchers.Default)

fun Array<ConstantState?>.newDrawables() = map { it?.newDrawable() }

fun Array<ConstantState?>.getChangingConfigurations() =
    fold(0) { last, dr -> last or (dr?.changingConfigurations ?: 0) }

fun createCSS(vararg drawables: Drawable?): Array<ConstantState?>? = drawables.map {
  if (it == null) null else it.constantState ?: return@createCSS null
}

class RetryLazy<T : Any>(private val initializer: () -> T?) : ReadOnlyProperty<Any?, T?> {
  @Volatile private var value: T? = null

  override fun getValue(thisRef: Any?, property: KProperty<*>): T? {
    value?.let {
      return it
    }
    return synchronized(this) {
      value ?: initializer()?.also { value = it }
    }
  }
}

fun <T : Any> retryLazy(initializer: () -> T?) = RetryLazy(initializer)

class ResettableLazy<T>(private val block: () -> T) : ReadOnlyProperty<Any?, T> {

  @Volatile private var cached: Any? = UNSET

  @Suppress("UNCHECKED_CAST")
  fun get(): T {
    val c = cached
    if (c !== UNSET) return c as T
    return synchronized(this) {
      val c2 = cached
      if (c2 !== UNSET) c2 as T else block().also { cached = it }
    }
  }

  fun reset() {
    cached = UNSET
  }

  override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()

  private object UNSET
}

fun <T> resettableLazy(block: () -> T) = ResettableLazy(block)

fun isAppGrantedRoot() =
    System.getenv("PATH")?.split(File.pathSeparator)?.any { dir ->
      dir.isNotEmpty() && File(dir, "su").canExecute()
    } ?: false
