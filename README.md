# Merged into `mail`

两个原子唯一的差别是凭据参数名（`user`/`password` 与 `smtpUser`/`smtpPassword`）。`mail` 2.1.0 起两种名字都认，
且都是「明文参数或解析后的 secret」两条路径都取。

```groovy
// 旧
runAtom(atom: 'mail-ext', version: '1.latest', inputs: [smtpUser: '...', smtpPassword: '...', ...])
// 新（参数名可以原样保留）
runAtom(atom: 'mail', version: '2.latest', inputs: [smtpUser: '...', smtpPassword: '...', ...])
```

本仓库只读保留，便于历史构建追溯。
