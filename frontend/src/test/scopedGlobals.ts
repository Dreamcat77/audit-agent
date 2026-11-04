// Browser hook fixtures must restore both property values and prior absence.
export function installScopedGlobals(values: Record<string, unknown>): () => void {
  const originals = Object.keys(values).map((key) => (
    [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const
  ));
  Object.assign(globalThis, values);
  return () => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else Reflect.deleteProperty(globalThis, key);
    }
  };
}
