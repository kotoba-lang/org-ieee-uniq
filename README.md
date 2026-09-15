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

Forty-nine cases, all byte-identical. No locale: `uniq` compares lines for
**equality**, not order, so it has no collation to disagree about — and for
the same reason it needed no ordering primitive, `string=` being the whole
comparison.

`uniq` does **not** add a newline to an unterminated last line. Measured
2026-09-15: `a\nb` is three bytes in and three out, with `-c` too. This
README said the opposite until then, from the fixture `a\na` — three bytes
in, two out — whose one run *inherits* the first line's newline, so it could
not tell the newline uniq adds from the newline the input had. A run is
written with a newline exactly when its **first** line was terminated;
`nonl2` (`a\nb`, alone, with `-c`, and to the output operand) is the case
that separates the two, and the previous guest fails all three.

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

## The output is written as it goes, or built at the pool's tail (2026-09-15)

The walk built the whole answer by `(string-concat acc run)`, copying the
accumulator at every run — quadratic in the pool. Measured: a 13,000-line,
800 KB file trapped. To stdout the answer is now **written run by run** and
nothing accumulates. To the output operand it is still one value (the write
wire takes the whole content), built with the loader's **tail append**:
`string-concat` copies only its second operand when the first is the pool's
last allocation (amu, 2026-09-15), so an accumulator kept at the tail grows
by the bytes appended. That is a discipline in the guest — append only
views (a line of the input, a literal, one digit at a time), and make no
capability call between appends, since every wire answer is interned in the
pool and moves the tail — and the flags are read once into `mode` for that
reason.

Measured on a 33 MB file (769,400 lines), CPU seconds, identical output to
`/usr/bin/uniq`: plain 0.58 s, `-c` 0.75 s, `-d` 0.41, `-u` 0.56, `-i` 0.59;
`/usr/bin/uniq -c` 0.18 s. The suite's 80,000-line fixture runs every flag
and the output operand.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37). Fuel, the string
arena, the pair heap, the grant and the filesystem scope are all constants of
the packaged binary.

## The second operand is a DESTINATION

`uniq INPUT OUTPUT` writes the answer to OUTPUT and puts **nothing** on
stdout, truncating OUTPUT if it exists. Measured 2026-09-10. That makes uniq
the only command here whose extra operand is not another input — and the
reason its walk builds a string rather than writing as it goes: the answer
has to exist as a value before its destination is known. The whole output
therefore lives in one guest string, the same bound `cat` and `cp` work
under.

Three or more operands is a usage error, exit 1, with uniq's own usage line.

### The file is compared, not just the streams

Both sides produce empty stdout and exit 0 when writing to a destination, so
a suite comparing only the streams would pass an implementation that wrote
**nothing at all**. The control says exactly that: never writing the
destination fails all five cases that have one — including `empty OUT`, so
creating an empty destination is genuinely checked — while correctly leaving
`missing OUT` passing.

A missing INPUT leaves the destination untouched: not created, and not
created-then-empty, because the input is checked first. Creating the
destination before that check fails exactly one case, `missing OUT`, and no
other.

### A destination case whose input does not exist tests nothing

These cases were first written against a fixture named `dups`, which does not
exist. They passed — both implementations reported the same missing file,
wrote nothing, and agreed. Now they use `adj`, which does.

## `-d`, `-u` and `-i`

```
-d   only lines that appeared MORE than once, one copy each
-u   only lines that appeared exactly once
-i   compare folded, print the first occurrence verbatim
```

`-d` and `-u` partition a file between them and neither is the whole of it —
over runs of 2, 1, 3 and 1, `-d` answers two lines and `-u` answers two
different ones. Both print **one** copy, not n.

`-i` folds for the COMPARISON only: over `A a B` it answers `A` and `B`, not
`a`. Folding is **ASCII A–Z only** — BSD `uniq -i` folds non-ASCII under this
locale and this does not, the same boundary
[`org-ieee-grep`](https://github.com/kotoba-lang/org-ieee-grep) draws for its
own `-i`.

Controls, each failing only its own cases: making `-d` keep every run fails 4
(`all` and `empty` survive correctly — for an all-duplicate file every run
*is* a repeat); making `-u` keep repeats instead fails a different 4; and
switching the fold off fails exactly the 2 `-i` cases.

### One flag index, not one per flag

`first-operand` keyed off `-c` alone, so every newly added flag had its
operand index wrong — `-d` was read as the input path and all thirteen new
cases failed identically at exit 1 while `-c` kept working. A uniform failure
across every new case, with the old ones green, is what that looks like.

## What this is not

No `-d`, `-u`, `-i`, `-f`, `-s`, no reading standard input — with no operand
this exits 1 rather than pretending to have read an empty one.
