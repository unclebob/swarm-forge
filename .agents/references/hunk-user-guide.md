# Hunk User Guide

Quick reference for [Hunk](https://github.com/modem-dev/hunk) — the review-first terminal diff viewer used in this fork.

## Open a diff (modified files only)

```bash
git diff upstream/main...HEAD --diff-filter=M | hunk patch -
```

`--diff-filter=M` shows only **modified** tracked files, excluding new/untracked files.

## Navigation

| Key | Action |
|-----|--------|
| `↑ / ↓` | move line by line |
| `Space` or `f` | page down |
| `b` or `Shift+Space` | page up |
| `d / u` | half page down / up |
| `[ / ]` | previous / next hunk |
| `, / .` | previous / next file |
| `{ / }` | previous / next comment |
| `← / →` | scroll code horizontally (Shift = faster) |
| `Home / End` or `g / G` | jump to top / bottom |

## View

| Key | Action |
|-----|--------|
| `1 / 2 / 0` | split / stack / auto layout |
| `s` | toggle sidebar |
| `t` | toggle theme |
| `a` | toggle AI notes |
| `z` | toggle unchanged context |
| `l / w / m` | toggle line numbers / wrap / metadata |
| `e` | open file in `$EDITOR` |

## Review

| Key | Action |
|-----|--------|
| `/` | focus file filter |
| `c` | create review note |
| `Tab` | toggle files/filter focus |
| `F10` | open menus |
| `r` | reload (watch mode) |
| `q` | quit |

## Mouse

- Wheel — scroll vertically
- Shift+Wheel — scroll horizontally

## In-app help

Press `?` or `h` inside Hunk to open the full controls help modal.
