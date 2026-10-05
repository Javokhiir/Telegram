import { kv as raw } from '@vercel/kv';

// The KV client, counting every Redis command it sends (pipelines count each queued command),
// so the Usage dashboard can compare our own traffic with the database plan limits.
let commands = 0;

export function takeCommandCount() {
  const c = commands;
  commands = 0;
  return c;
}

function countingPipeline(pipeline) {
  let queued = 0;
  const proxy = new Proxy(pipeline, {
    get(target, prop) {
      const value = target[prop];
      if (typeof value !== 'function') return value;
      if (prop === 'exec') {
        return (...args) => {
          commands += queued;
          queued = 0;
          return value.apply(target, args);
        };
      }
      return (...args) => {
        queued++;
        const result = value.apply(target, args);
        return result === target ? proxy : result;
      };
    },
  });
  return proxy;
}

export const kv = new Proxy(raw, {
  get(target, prop) {
    const value = target[prop];
    if (typeof value !== 'function') return value;
    if (prop === 'pipeline' || prop === 'multi') {
      return (...args) => countingPipeline(value.apply(target, args));
    }
    return (...args) => {
      commands++;
      return value.apply(target, args);
    };
  },
});

export const rawKv = raw;
