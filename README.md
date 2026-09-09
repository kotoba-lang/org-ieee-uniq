# kotoba-lang/org-ieee-uniq — POSIX `uniq`, as a Kotoba command binary

`uniq` from IEEE Std 1003.1 for one operand, written in `.kotoba` and
compiled to a standalone native executable.

```sh
./uniq FILE        # adjacent duplicate lines collapsed to one
./uniq -c FILE     # each run prefixed with its count
```

**Adjacent** is the whole contract: `a a b a` answers `a b a`, not `a b`.
That is what makes `sort | uniq` a pipeline rather than a redundancy.

## `-c` is `"%4d "`, and the boundary is cheap here

The count is right-aligned in **four** columns then a space. Measured
2026-09-10: `9` prints as `   9 `, `1000` as `1000 `, `10000` as `10000 ` —
so the four is a **minimum** and the space is unconditional.

A fixed five-wide field agrees on every count below 1000 and differs at it,
so the suite carries a 1000-line fixture. (`wc` needed ten million lines for
the same kind of boundary; this one costs a thousand.)

## Measured against the system utility

Sixteen cases, all byte-identical. No locale: `uniq` compares lines for
**equality**, not order, so it has no collation to disagree about — and for
the same reason it needed no ordering primitive, `string=` being the whole
comparison.

`uniq` **adds** the newline a last line lacks: `a\na` is three bytes in and
two out. Same as `grep` and `sort`, opposite of `head`.

Verified to fail as well as pass: collapsing *all* duplicates rather than
adjacent ones fails two cases, padding `-c` to a fixed five fails three, and
dropping the final flush fails two.

## The bound this command found

The 1000-line fixture did not pass at first. It trapped with

```
:heap {:capacity 4096 :used 4096}   :fuel {:remaining 49994368}
```

— the **pair heap**, not fuel and not the string arena. Every string handle
is a pair and nothing is reclaimed, and a line walk spends about eight per
line, so the pair heap is the bound a text command meets first. It was the
last fixed arena in the loader; it is a per-run budget now (`--pairs`,
`KEXE_PAIRS`), and this suite packages 200,000.

The report naming the arena is what made that one measurement instead of a
bisection.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37). Fuel, the string
arena, the pair heap, the grant and the filesystem scope are all constants of
the packaged binary.

## What this is not

One operand. No `-d`, `-u`, `-i`, `-f`, `-s`, no second (output) operand, no
reading standard input.
