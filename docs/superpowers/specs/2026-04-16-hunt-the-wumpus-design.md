# Hunt the Wumpus — Design Spec

## Overview

An exact replica of Gregory Yob's 1973 Hunt the Wumpus, implemented in Clojure.
Pure text/CLI interface faithful to the original game. Lives in `wumpus/` at
the swarm-forge project root.

## Architecture: Functional Core / Imperative Shell

All game logic is pure functions over immutable data. A thin I/O shell handles
stdin/stdout. This makes TDD straightforward — the core is tested without I/O.

## Project Structure

```
wumpus/
  deps.edn
  src/wumpus/
    dodecahedron.clj
    game.clj
    messages.clj
    main.clj
  spec/wumpus/
    dodecahedron_spec.clj
    game_spec.clj
    messages_spec.clj
  features/
    wumpus.feature
```

**Build tool**: Clojure CLI / deps.edn
**Test framework**: Speclj

## Module 1: dodecahedron.clj

Generates a 20-room cave map shaped as a dodecahedron. Each room connects to
exactly 3 others. The graph is built programmatically from the dodecahedron's
vertex/edge structure (top cap, upper ring, lower ring, bottom cap).

**Representation**: A map from room number (1-20) to a set of 3 adjacent room
numbers.

```clojure
{1 #{2 5 8}, 2 #{1 3 10}, ...}
```

**Public API**:

- `(make-cave-map)` — returns the adjacency map
- `(adjacent? cave-map room1 room2)` — predicate
- `(neighbors cave-map room)` — returns the 3 neighbors of a room

## Module 2: game.clj

Pure game state and logic. No side effects.

**Game state** — a single immutable map:

```clojure
{:cave-map   {1 #{2 5 8}, ...}
 :player     1
 :wumpus     12
 :pits      #{3 14}
 :bats      #{6 18}
 :arrows     5
 :status     :playing}
```

Status values: `:playing`, `:win`, `:lose-wumpus`, `:lose-pit`, `:lose-arrow`

**Initialization**:

- `(new-game cave-map)` — places player, wumpus, pits, and bats in distinct
  random rooms. Returns the initial state.

**Query functions**:

- `(sensed-hazards state)` — returns a set of keywords (`:wumpus`, `:pit`,
  `:bats`) based on what is adjacent to the player
- `(player-room state)` — returns current room number
- `(player-neighbors state)` — returns the 3 adjacent rooms

**Action functions** — each takes state, returns new state:

- `(move state room)` — move player to adjacent room, then resolve:
  - Wumpus room: 50% wumpus moves to random neighbor, 50% `:lose-wumpus`
  - Pit room: `:lose-pit`
  - Bat room: player transported to random room, re-resolve (bats stay put)
- `(shoot state path)` — fire arrow along path of 1-5 rooms:
  - Arrow follows the path; invalid rooms deflect randomly to a neighbor of the
    arrow's current position
  - Arrow enters wumpus room: `:win`
  - Arrow enters player room: `:lose-arrow`
  - Miss: decrement arrows, wumpus 75% chance to move to neighbor
  - Arrows reach 0 after miss: `:lose-arrow`

**Randomness**: Functions that need randomness accept a random-number function
parameter so tests can supply deterministic outcomes.

## Module 3: messages.clj

Reproduces the original game's text output. Pure functions returning strings.

- `(room-description room neighbors)` — "You are in room 5. Tunnels lead to 1, 4, 8."
- `(hazard-warning hazard-key)`:
  - `:wumpus` -> "I smell a Wumpus!"
  - `:pit` -> "I feel a draft!"
  - `:bats` -> "Bats nearby!"
- `(outcome-message status)`:
  - `:win` -> "Hee hee hee, the Wumpus'll get you next time!!"
  - `:lose-wumpus` -> "Tsk tsk tsk - Wumpus got you!"
  - `:lose-pit` -> "YYYIIIIEEEE . . . fell in pit"
  - `:lose-arrow` -> "Ouch! Arrow got you!"
- `(shoot-prompt)`, `(move-or-shoot-prompt)`, `(room-prompt)` — input prompts
- `(intro)` — opening banner

## Module 4: main.clj — I/O Shell

Thin adapter wiring game logic to stdin/stdout. All side effects live here.

**Input format** — single-line commands, no sub-prompts:

- `M 5` — move to room 5
- `S 3 7 12` — shoot arrow through rooms 3, 7, 12

**Game loop**:

1. Print intro
2. Initialize state via `(new-game (make-cave-map))`
3. While status is `:playing`:
   - Print hazard warnings for adjacent threats
   - Print room description (room number and neighbor numbers)
   - Read one input line, parse command
   - Apply `move` or `shoot`, get new state
4. Print outcome message
5. Prompt "Same setup (Y-N)?" — Y replays same cave layout with reset state

**Input validation**: The shell handles parsing and re-prompts on bad input.
The pure core never sees invalid data.

**Entry point**: `-main` function, runnable via `clj -M -m wumpus.main`

## Testing Strategy

### Speclj Specs

**dodecahedron_spec**:
- 20 rooms, each with exactly 3 neighbors
- Symmetry: if A connects to B then B connects to A
- Graph is connected (can reach any room from any other)
- No self-loops

**game_spec** (deterministic RNG for all random outcomes):
- Move to empty room: player position changes, nothing else
- Move to wumpus room: lose (wumpus stays) or wumpus flees (controlled)
- Move to pit: lose
- Move to bats: player relocated to random room, re-resolve
- Shoot hits wumpus: win
- Shoot hits player: lose
- Shoot misses: arrows decrement, wumpus may move
- Invalid room in arrow path: random deflection
- Last arrow missed: lose
- sensed-hazards returns correct set for adjacent threats

**messages_spec**:
- Each message function returns the expected string

### Gherkin E2E Scenarios

Full game flows exercised through the I/O shell:
- Win by shooting the wumpus
- Lose to wumpus (enter its room)
- Lose to pit
- Bat relocation
- Arrow exhaustion
- Arrow hits self

### Mutation Testing

Run against game.clj and dodecahedron.clj. Target 90%+ kill rate.

### Complexity

All functions stay at cyclomatic complexity <= 4. CRAP score < 30.
