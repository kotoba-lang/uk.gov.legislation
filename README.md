# uk.gov.legislation

`legislation.gov.uk` から取得した、**英国 Public General Act の全文（改正反映版）** を保全する DataLad dataset です。統合用 datom は `etzhayyim/global-legislation-datoms` が生成します。

## 現在地（2026-08-04 実測）

| | |
|---|---|
| 対象 | UK Public General Acts (`ukpga`) **1801–2026** |
| 件数 | **3,267**（当該クラス全件。取得失敗 0） |
| 全文バイト数 | 1,594,186,594 |
| 依存辺 | **103,333**（うち改正辺 **55,505**） |

## レイヤ

| パス | 中身 | git での扱い |
|---|---|---|
| `raw/feed/ukpga-<year>.atom` | 年ごとの `data.feed`（その年の全 Act） | git-annex → B2 |
| `raw/acts/ukpga-<year>-<n>.xml` | `data.xml` = CLML 全文（改正反映済み） | git-annex → B2 |
| `raw/source-catalog.edn` | 全ファイルの sha256 | **git 本体** |
| `index/laws.edn` / `index/relations.edn` | 消費者が読む索引 | **git 本体** |

## 依存辺 — CLML から読む

legislation.gov.uk は改正反映版の本文中で、他の法令への参照を
`<Citation URI="http://www.legislation.gov.uk/id/ukpga/1998/42">` として**構造化して**持っています。ここから 2 種類の辺を区別して取り出します:

- **`:law.rel/amends`** — `<Commentary Type="F">`（textual amendment の脚注）の**内側**にある引用。その脚注は「ある法律が別の法律を実際に書き換えた」記録そのものなので、これは改正辺です。
- **`:law.rel/cites`** — それ以外の引用。

**この区別が本質です。**「この法律を改正したのはどれか」と「この法律に言及しているのはどれか」は別の問いで、両方を `cites` に潰すと前者が答えられなくなります。

引用 URI に条項（`/section/3`）が付いていても**法令単位に丸めています**。辺は法令間のものであり、条項まで保持すると数百の辺が数十万になるのに答えられる問いは増えません。

## wave-1 で取っていないもの

`raw/source-catalog.edn` の `:catalog/known-gaps` に記録済み:

- **UKSI（statutory instruments、10 万件超）** — 別クラス。
- **委譲立法**（`asp` スコットランド / `anaw`・`asc` ウェールズ / `nia` 北アイルランド）。
- **1801 年以前の法令** — legislation.gov.uk 上には存在しますが、このクラスの外です。

## ライセンス

**Open Government Licence v3.0**。出典表示のもとで複製・改変・商用利用が可能。**Tier-A**。

## 再取得 / 再生成

```bash
nbb --classpath bin bin/fetch.cljs --pool 4 --from 1801 --to 2026
nbb --classpath bin bin/index.cljs
```

取得は再開可能です。実測 2026-08-04、pool 4 の 1 回目で 1984–1985 年の 27 件が連続して失敗しました（上流のレート制限と見られる）——`--pool 2` で同じ範囲を再実行して全件揃いました。失敗は握り潰さずログに ID を出すので、この種の穴は再実行で埋まります。
