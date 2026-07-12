import Foundation

/// Lock-protected storage for the package's single-call invariant.
///
/// Admission and insertion are one operation. A separate `isIdle` check plus
/// a later insert lets simultaneous PushKit, JS, and outgoing paths all see an
/// empty registry and admit multiple calls.
final class SingleCallRegistry<Value> {
  private let lock = NSLock()
  private var entry: (id: UUID, value: Value)?

  @discardableResult
  func reserveIfIdle(id: UUID, value: Value) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard entry == nil else {
      return false
    }
    entry = (id, value)
    return true
  }

  func value(for id: UUID) -> Value? {
    lock.lock()
    defer { lock.unlock() }
    guard entry?.id == id else {
      return nil
    }
    return entry?.value
  }

  func first() -> Value? {
    lock.lock()
    defer { lock.unlock() }
    return entry?.value
  }

  func contains(_ id: UUID) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return entry?.id == id
  }

  @discardableResult
  func mutate(_ id: UUID, _ transform: (inout Value) -> Void) -> Value? {
    lock.lock()
    defer { lock.unlock() }
    guard var current = entry, current.id == id else {
      return nil
    }
    transform(&current.value)
    entry = current
    return current.value
  }

  @discardableResult
  func remove(_ id: UUID) -> Value? {
    lock.lock()
    defer { lock.unlock() }
    guard let current = entry, current.id == id else {
      return nil
    }
    entry = nil
    return current.value
  }
}
