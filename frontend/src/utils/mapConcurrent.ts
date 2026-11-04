/** Preserve input order while keeping history/result reads bounded. */
export async function mapConcurrent<T, R>(values: readonly T[], limit: number, read: (value: T) => Promise<R>): Promise<R[]> {
  if (!Number.isInteger(limit) || limit < 1) throw new Error('Invalid concurrency');
  const results = new Array<R>(values.length);
  let position = 0;
  await Promise.all(Array.from({ length: Math.min(limit, values.length) }, async () => {
    while (position < values.length) {
      const index = position++;
      results[index] = await read(values[index]);
    }
  }));
  return results;
}
