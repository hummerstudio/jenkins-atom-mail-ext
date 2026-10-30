# jenkins-atom-mail-ext（已合并到 mail 2.x，仅保留兼容）

> 这个原子的令牌能力已并入 `jenkins-atom-mail` 2.0.0，新任务请直接用 `mail`。
> 1.0.0 保留是为了已引用它的流水线不突然失败，不再新增功能。

# jenkins-atom-mail-ext

Email (tokens) — port of **email-ext** (193,679) as an atom.

触发时机（按结果发）留给 runner，原子只负责按令牌渲染并发送。

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
