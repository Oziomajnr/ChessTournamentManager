# Chess Tournament Pairing Algorithms

## Introduction

This application supports two tournament formats: **Swiss** and **Round Robin**. Each format has distinct rules, use cases, and pairing algorithms. This document explains both systems, their rules, and the algorithms that implement them.

---

## Tournament Types at a Glance

| | Swiss System | Round Robin |
|---|---|---|
| **Concept** | Players with similar scores are paired each round | Every player plays every other player exactly once |
| **Rounds** | ⌈log₂(n)⌉ (declared beforehand) | n − 1 (even *n*) or n (odd *n*) |
| **Best for** | Large fields (20+ players) | Small fields (4–12 players) |
| **Determines winner in** | Few rounds relative to field size | Maximum rounds — most thorough |
| **Rematches** | Forbidden | By design — every possible matchup occurs |
| **Pairing complexity** | High (constraint satisfaction) | Low (deterministic rotation) |
| **Bye handling** | Lowest-scored non-bye'd player, validated | Automatic via phantom player slot |
| **Colour balancing** | Active — enforced per-player across rounds | Passive — alternated by board position |

### When to Use Each Format

```
                        Number of Players
             4     8     12    16     20     32     64
             │     │      │     │      │      │      │
Round Robin  ├─────┤──────┤     │      │      │      │
             │ 3–11 rounds │     │      │      │      │
             │  (ideal)    │     │      │      │      │
             │             │     │      │      │      │
Swiss        │     ├──────┼─────┼──────┼──────┼──────┤
             │     │ 3–5 rounds  │  4–5 rounds │ 5–6  │
             │     │ (efficient) │  (standard) │rounds│
             └─────┴──────┴─────┴──────┴──────┴──────┘
```

---

# Part I: Round Robin System

## Round Robin Rules

The Round Robin system is the simpler of the two formats. Its rules are straightforward:

| Rule | Description |
|------|-------------|
| **RR1** | Every participant plays every other participant exactly once. |
| **RR2** | The total number of rounds is *n* − 1 for even *n*, or *n* for odd *n* (where *n* is the number of players). |
| **RR3** | If the number of players is odd, each player receives exactly one bye across the entire tournament. |
| **RR4** | All pairings for all rounds are determined before the tournament begins (deterministic). |
| **RR5** | Colours alternate by board position and round to ensure approximate balance. |

## Round Robin Algorithm: Circle Method

The algorithm uses the classic **circle (polygon) rotation method**, which produces all required matchups in a deterministic sequence.

### How It Works

1. Sort players by rating (highest first).
2. If the player count is odd, add a phantom **BYE** slot to make it even.
3. **Fix** the first player in position 0.
4. Place the remaining *n* − 1 players in a rotating list.
5. For each round, rotate the list by one position, then pair position *i* with position *n* − 1 − *i*.

```
Round 1 (initial):        Round 2 (rotate →):       Round 3 (rotate →):

    ┌──[1]──┐                  ┌──[1]──┐                 ┌──[1]──┐
    │       │                  │       │                  │       │
   [2]     [6]                [6]     [5]                [5]     [4]
    │       │                  │       │                  │       │
   [3]     [5]                [2]     [4]                [6]     [3]
    │       │                  │       │                  │       │
    └──[4]──┘                  └──[3]──┘                  └──[2]──┘

Pairs:                      Pairs:                     Pairs:
  1↔6, 2↔5, 3↔4             1↔5, 6↔4, 2↔3             1↔4, 5↔3, 6↔2
```

Player 1 is fixed at the top. The remaining players [2–6] rotate clockwise each round. Pairing connects the left column to the right column at matching heights.

For 6 players (positions 0–5):

| Round | Arrangement | Board 1 | Board 2 | Board 3 |
|:-----:|------------|:-------:|:-------:|:-------:|
| 1 | [1, 2, 3, 4, 5, 6] | 1 vs 6 | 2 vs 5 | 3 vs 4 |
| 2 | [1, 6, 2, 3, 4, 5] | 1 vs 5 | 6 vs 4 | 2 vs 3 |
| 3 | [1, 5, 6, 2, 3, 4] | 1 vs 4 | 5 vs 3 | 6 vs 2 |
| 4 | [1, 4, 5, 6, 2, 3] | 1 vs 3 | 4 vs 2 | 5 vs 6 |
| 5 | [1, 3, 4, 5, 6, 2] | 1 vs 2 | 3 vs 6 | 4 vs 5 |

Every pair of players meets exactly once across all 5 rounds.

### Colour Assignment

Colours are assigned by a simple formula based on board index and round number:

```
if (boardIndex + roundNumber) is even → first listed plays White
if (boardIndex + roundNumber) is odd  → second listed plays White
```

This alternation ensures that each player gets a roughly equal number of white and black games across the tournament, without requiring the complex constraint-solving that the Swiss system needs.

### Bye Handling (Odd Player Count)

When the player count is odd, a phantom BYE player (ID = −1) is added to the rotation. Any player paired against the phantom receives a bye for that round:

```
5 players → add BYE slot → 6 positions → 5 rounds

Round where Player X is opposite BYE:
  → Player X gets a bye (1 point, no game)
  → Every player gets exactly 1 bye across the tournament
```

### Round Robin Complexity

| Aspect | Value |
|--------|-------|
| Time per round | O(n) — direct index computation |
| Total matchups | n(n−1)/2 |
| Total rounds | n − 1 (even) or n (odd) |
| Backtracking needed | Never — fully deterministic |

---

# Part II: Swiss System

## Swiss System Introduction

The Swiss system is the most widely used tournament format in chess. It allows a large number of players to compete in a limited number of rounds while ensuring competitive pairings throughout. Unlike round-robin (where everyone plays everyone), a Swiss tournament with *n* players typically requires only ⌈log₂(n)⌉ rounds to produce a clear winner.

The pairing algorithm implemented in `SwissPairingEngine` enforces the FIDE C.04.1 Swiss system rules through a three-phase approach: **bye selection**, **matching with backtracking**, and **global colour assignment**.

## FIDE Swiss System Rules Reference

The algorithm enforces the following rules from the FIDE Handbook (C.04.1):

| Rule | Description | Constraint Type |
|------|-------------|-----------------|
| **R1** | The number of rounds is declared beforehand. | Structural (external) |
| **R2** | Two participants shall not play against each other more than once. | **Hard** |
| **R3** | If the number of participants is odd, one receives a pairing-allocated bye worth 1 point. | **Hard** |
| **R4** | A participant who has already received a bye shall not receive another. | **Hard** |
| **R5** | In general, participants are paired with others with the same score. | Soft (best-effort) |
| **R6** | For each participant, \|whites − blacks\| shall not exceed 2. | **Hard** (exceptions allowed) |
| **R7** | No participant shall receive the same colour three times in a row. | **Hard** (exceptions allowed) |
| **R8** | A participant is given the colour with which they played fewer games. If balanced, alternate from the last colour played. | Soft (preference) |

> Rules R6 and R7 are treated as hard constraints during the primary search. If no solution exists under those constraints, the algorithm relaxes them per the FIDE provision: *"Each pairing system may have exceptions to this rule."*

---

## Algorithm Overview

The algorithm runs in three sequential phases for each round:

```
┌─────────────────────────────────────────────────────────┐
│                    INPUT                                 │
│  Players (sorted by score ↓, then rating ↓)             │
│  Previous pairings from all completed rounds             │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
         ┌─────────────────────────────┐
         │  PHASE 1: Bye Selection     │
         │  (R3, R4)                   │
         │                             │
         │  If odd player count:       │
         │  → Pick bye recipient       │
         │  → Remove from pairing pool │
         └─────────────┬───────────────┘
                       │
                       ▼
         ┌─────────────────────────────┐
         │  PHASE 2: Player Matching   │
         │  (R2, R5, R6, R7)          │
         │                             │
         │  Backtracking search for    │
         │  complete matching of all   │
         │  players in the pool        │
         └─────────────┬───────────────┘
                       │
                       ▼
         ┌─────────────────────────────┐
         │  PHASE 3: Colour Assignment │
         │  (R6, R7, R8)              │
         │                             │
         │  Global backtracking over   │
         │  all pairs simultaneously   │
         └─────────────┬───────────────┘
                       │
                       ▼
         ┌─────────────────────────────┐
         │          OUTPUT             │
         │  List of Pairing objects    │
         │  (board, white, black,      │
         │   round, result)            │
         └─────────────────────────────┘
```

---

## Phase 1: Bye Selection

When the player count is odd, exactly one player must receive a bye (R3). The bye is worth 1 point and has no opponent or colour.

### Priority Order

Bye candidates are sorted by a composite key:

| Priority | Criterion | Rationale |
|----------|-----------|-----------|
| 1 (highest) | Has **not** received a previous bye | R4: no double byes |
| 2 | **Lowest** current score | Minimises competitive impact |
| 3 | **Lowest** rating | Tiebreaker among equal scores |

### Bye Validation

The algorithm doesn't blindly assign the top-priority bye candidate. It verifies that removing that player from the pool still allows a **complete matching** for the remaining (now even) players. If not, it tries the next candidate.

```
Bye Candidates (sorted):      Can remaining players
                                be fully matched?
  ┌─────────────────────┐
  │ Player E (0 pts)    │───── YES ──→ ✓ Assign bye to E
  └─────────────────────┘
  ┌─────────────────────┐
  │ Player D (0.5 pts)  │     (not tried — E worked)
  └─────────────────────┘
  ┌─────────────────────┐
  │ Player A (1.0 pts)  │
  └─────────────────────┘
           ...
```

If *no* bye candidate yields a valid matching (extremely rare, only in theoretical edge cases), the algorithm falls back to the first candidate.

---

## Phase 2: Player Matching

This is the core of the algorithm. Given an even-sized pool of players, find a **complete matching** — a set of pairs where every player appears exactly once — subject to constraints.

### Constraints Checked Per Pair

| Check | Rule | Action if violated |
|-------|------|--------------------|
| Rematch? | R2 | **Skip** this pair |
| Valid colour assignment exists? | R6, R7 | **Skip** this pair |

A pair is only accepted if (a) the two players have never played each other before, and (b) at least one of the two colour assignments (A=White/B=Black or B=White/A=Black) is legal under R6 and R7.

### Candidate Ordering (R5, R8)

For each unmatched player, opponents are tried in this order:

| Priority | Criterion | Rationale |
|----------|-----------|-----------|
| 1 | Smallest \|score difference\| | R5: same-score pairing |
| 2 | Highest rating | Standard Swiss tiebreaker |

This ordering means the algorithm naturally tries to pair players within the same score group before crossing score brackets.

### Backtracking Search

The matching uses depth-first backtracking:

```
findMatching(pool):
    if pool is empty → SUCCESS (all paired)

    first ← first unpaired player (by score rank)
    candidates ← remaining unpaired, sorted by score proximity

    for each candidate:
        if {first, candidate} is a rematch → skip
        if no legal colour exists for {first, candidate} → skip

        tentatively pair (first, candidate)
        recurse on remaining players

        if recursion succeeds → return solution
        else → undo pair, try next candidate

    return FAILURE (backtrack)
```

#### Walkthrough Example

8 players after Round 1. Scores: A=1, B=1, C=1, D=1, E=0, F=0, G=0, H=0.

Round 1 pairings were: A-E, B-F, C-G, D-H.

```
Score groups:  [A=1, B=1, C=1, D=1]  [E=0, F=0, G=0, H=0]

Matching attempt (Round 2):
  A (1.0) → try B (1.0, same score)  → not a rematch ✓ → pair A-B ✓
  C (1.0) → try D (1.0, same score)  → not a rematch ✓ → pair C-D ✓
  E (0.0) → try F (0.0, same score)  → not a rematch ✓ → pair E-F ✓
  G (0.0) → try H (0.0, same score)  → not a rematch ✓ → pair G-H ✓

Result: A-B, C-D, E-F, G-H  ← all same-score pairs ✓
```

If A-B were a rematch (they played in a hypothetical earlier round), the algorithm would try A-C, then A-D, backtracking as needed until a complete matching is found.

### Three-Level Fallback

When the primary search fails, the algorithm progressively relaxes constraints:

```
┌──────────────────────────────────────────────────────┐
│ Level 1: Full constraints                            │
│   No rematches + colour feasibility required         │
│   (R2 + R6 + R7)                                    │
└──────────┬───────────────────────────────────────────┘
           │ fails
           ▼
┌──────────────────────────────────────────────────────┐
│ Level 2: Relax colour constraint                     │
│   No rematches only (R2)                             │
│   FIDE allows R6/R7 exceptions                       │
└──────────┬───────────────────────────────────────────┘
           │ fails
           ▼
┌──────────────────────────────────────────────────────┐
│ Level 3: Allow rematches                             │
│   Complete matching guaranteed                       │
│   Rematches deprioritised (tried last)               │
│   Only reached when rounds ≈ n−1                     │
└──────────────────────────────────────────────────────┘
```

Level 3 is a safety net for extreme edge cases (e.g., 6 players in 5 rounds, where the number of rounds approaches the theoretical maximum). In standard Swiss formats — ⌈log₂(n)⌉ rounds — only Level 1 is ever needed.

---

## Phase 3: Colour Assignment

After the matching is determined, colours must be assigned to each pair. This is a separate optimisation problem because assigning colours to one pair affects the legality of colours for other pairs (through R6 and R7).

### Per-Player Colour Legality (R6, R7)

For a proposed colour assignment, two checks are performed:

```
isColorLegal(player, colour):

    R6 check:
        newWhites = whites + (1 if colour=W else 0)
        newBlacks = blacks + (1 if colour=B else 0)
        if |newWhites − newBlacks| > 2 → ILLEGAL

    R7 check:
        history = player's colour sequence (chronological)
        if last 2 colours == proposed colour → ILLEGAL
              (would create 3 in a row)

    → LEGAL
```

#### R6 Example

| Player | Whites so far | Blacks so far | Diff | Can play White? | Can play Black? |
|--------|:---:|:---:|:---:|:---:|:---:|
| Alice  | 3 | 2 | +1 | Yes (diff→+2) | Yes (diff→0) |
| Bob    | 1 | 3 | −2 | Yes (diff→−1) | No (diff→−3) |
| Carol  | 4 | 2 | +2 | No (diff→+3) | Yes (diff→+1) |

#### R7 Example

| Player | Colour History | Can play White? | Can play Black? |
|--------|:-:|:---:|:---:|
| Alice  | W, B, W | Yes (WBW**W** — no 3 in a row) | Yes |
| Bob    | B, B, W | Yes | Yes (BBW**B** — no 3 in a row) |
| Carol  | W, W, B | Yes (WWB**W** — no 3 in a row) | Yes |
| Dave   | B, B, _ | Yes | No (BB**B** — 3 in a row!) |

### R8 Colour Preference

When both colour options are legal, the algorithm selects based on R8:

```
colorPreference(player):
    if whites < blacks → prefers White
    if blacks < whites → prefers Black
    if balanced:
        if last colour was W → prefers Black (alternate)
        if last colour was B → prefers White (alternate)
```

### Global Backtracking

Rather than assigning colours greedily pair-by-pair (which can box later pairs into impossible states), the algorithm uses **global backtracking** over all pairs:

```
assignAllColors(pairs):
    if all pairs assigned → SUCCESS

    (a, b) ← next unassigned pair
    options ← [(a=W, b=B), (b=W, a=B)] ordered by R8 preference

    for each (white, black) in options:
        if isColorLegal(white, W) AND isColorLegal(black, B):
            apply assignment
            recurse on remaining pairs

            if recursion succeeds → return solution
            else → undo assignment, try other option

    return FAILURE (backtrack)
```

#### Why Global Backtracking Matters

Consider 4 pairs where greedy (left-to-right) assignment fails:

```
Greedy approach (fails):
  Pair 1: Alice(WW) vs Bob(BB)
          → Alice needs B, Bob needs W → assign Bob=W, Alice=B ✓
  Pair 2: Carol(WW) vs Dave(WB)
          → Carol needs B → assign Dave=W, Carol=B ✓
  Pair 3: Eve(BW) vs Frank(BW)
          → Both neutral → assign Eve=W, Frank=B ✓
  Pair 4: Grace(BB) vs Hank(BB)
          → Both NEED White → IMPOSSIBLE ✗

Global backtracking (succeeds):
  Pair 1: Alice=B, Bob=W ✓
  Pair 2: Carol=B, Dave=W ✓
  Pair 3: Eve=B, Frank=W ✓    ← different choice
  Pair 4: Grace=W, Hank=B ✓   ← now possible!
```

By reconsidering Pair 3's assignment (which was equally valid either way), the algorithm makes room for Pair 4.

---

## Data Structures

### Input State (built from previous rounds)

| Structure | Type | Purpose |
|-----------|------|---------|
| `matchupSet` | `Set<Set<Long>>` | All previous opponent pairs, for R2 rematch detection |
| `byeReceivers` | `Set<Long>` | Player IDs who received a bye, for R4 |
| `whiteCount` | `Map<Long, Int>` | Per-player count of white games, for R6 |
| `blackCount` | `Map<Long, Int>` | Per-player count of black games, for R6 |
| `colorHistory` | `Map<Long, List<Char>>` | Per-player chronological colour sequence (`W`/`B`), for R7 |

### Output

Each round produces a `List<Pairing>`:

| Field | Type | Description |
|-------|------|-------------|
| `tournamentId` | `Long` | Foreign key to tournament |
| `roundNumber` | `Int` | Current round (1-indexed) |
| `board` | `Int` | Board number (1-indexed) |
| `whitePlayerId` | `Long` | Player with white pieces |
| `blackPlayerId` | `Long?` | Player with black pieces (`null` for bye) |
| `result` | `PairingResult` | `PENDING`, `WHITE_WINS`, `BLACK_WINS`, `DRAW`, or `BYE` |

---

## Complexity Analysis

| Phase | Worst Case | Typical Case | Notes |
|-------|-----------|-------------|-------|
| **Bye selection** | O(n × matching) | O(1 × matching) | Usually first candidate works |
| **Matching** | O(n!! ) | O(n) | Backtracking with pruning; score-group ordering makes early termination likely |
| **Colour assignment** | O(2^(n/2)) | O(n/2) | 2 options per pair; pruning eliminates most branches |
| **Overall** | Exponential | **Linear** | For ⌈log₂(n)⌉ rounds, backtracking is almost never triggered |

In practice, with standard Swiss round counts, the algorithm runs in milliseconds even for 32+ players.

---

## Property-Based Testing

The algorithm is verified via **simulation-based property testing**. Each test:

1. Generates random players with random ratings (1200–2800)
2. Simulates a full tournament, round by round
3. After each round, assigns random results (win/loss/draw)
4. **Verifies all FIDE rules** after every generated round

### Test Matrix

| Test | Players | Rounds | Seeds | Result Scenario |
|------|:-------:|:------:|:-----:|-----------------|
| 4-player | 4 | 2 | 50 | Random |
| 5-player (odd) | 5 | 3 | 50 | Random |
| 6-player | 6 | 3 | 50 | Random |
| 7-player (odd) | 7 | 3 | 50 | Random |
| 8-player | 8 | 3 | 50 | Random |
| 10-player | 10 | 4 | 30 | Random |
| 12-player | 12 | 4 | 30 | Random |
| 16-player | 16 | 4 | 20 | Random |
| 20-player | 20 | 5 | 20 | Random |
| 3-player (min) | 3 | 2 | 50 | Random |
| 32-player (large) | 32 | 5 | 10 | Random |
| All decisive | 10 | 4 | 30 | White wins or black wins (no draws) |
| All draws | 10 | 4 | 30 | Every game is a draw |
| White always wins | 10 | 4 | 30 | White wins every game |
| Extended rounds | 12 | 5 | 20 | Random (above standard count) |
| Single round | 3–20 | 1 | 5 each | Random |
| Varied random | 4–24 | ⌈log₂(n)⌉ | 100 | Random |

**Total: 17 test methods, ~900 individual tournament simulations, ~2700 rounds verified.**

### Rule Verification Per Round

After each round, these verifiers run:

```
verifyRule2_NoRematches         → scans all matchups for duplicates
verifyRule3_ByeForOddPlayers    → checks bye count and result type
verifyRule4_NoDoubleByes        → cross-references previous bye receivers
verifyRule6_ColorDifference     → computes |W−B| for every player
verifyRule7_NoThreeConsecutive  → scans colour histories for streaks
verifyAllPlayersPairedOnce      → set comparison of expected vs actual
```

---

## End-to-End Example

### Setup

6 players, 3 rounds (⌈log₂(6)⌉ = 3).

| Seed | Player | Rating |
|:----:|--------|:------:|
| 1 | Magnus | 2850 |
| 2 | Hikaru | 2780 |
| 3 | Fabiano | 2760 |
| 4 | Ding | 2740 |
| 5 | Ian | 2720 |
| 6 | Anish | 2700 |

### Round 1

All players have 0 points. Sorted by rating, the matching pairs top-half vs bottom-half by score proximity (all scores equal, so rating ordering):

| Board | White | Black | Result |
|:-----:|-------|-------|:------:|
| 1 | Magnus (2850) | Ding (2740) | 1-0 |
| 2 | Hikaru (2780) | Ian (2720) | ½-½ |
| 3 | Fabiano (2760) | Anish (2700) | 0-1 |

Colour history after Round 1:

| Player | History | W | B | Diff |
|--------|:-------:|:-:|:-:|:----:|
| Magnus | W | 1 | 0 | +1 |
| Hikaru | W | 1 | 0 | +1 |
| Fabiano | W | 1 | 0 | +1 |
| Ding | B | 0 | 1 | −1 |
| Ian | B | 0 | 1 | −1 |
| Anish | B | 0 | 1 | −1 |

### Round 2

Standings: Magnus=1, Anish=1, Hikaru=0.5, Ian=0.5, Ding=0, Fabiano=0.

Score groups: {Magnus, Anish}, {Hikaru, Ian}, {Ding, Fabiano}.

| Board | White | Black | Rationale | Result |
|:-----:|-------|-------|-----------|:------:|
| 1 | Anish (B→W) | Magnus (W→B) | Same score. Both balance colours. | ½-½ |
| 2 | Ian (B→W) | Hikaru (W→B) | Same score. Both balance colours. | 1-0 |
| 3 | Ding (B→W) | Fabiano (W→B) | Same score. Both balance colours. | ½-½ |

Colour history after Round 2:

| Player | History | W | B | Diff |
|--------|:-------:|:-:|:-:|:----:|
| Magnus | W, B | 1 | 1 | 0 |
| Hikaru | W, B | 1 | 1 | 0 |
| Fabiano | W, B | 1 | 1 | 0 |
| Ding | B, W | 1 | 1 | 0 |
| Ian | B, W | 1 | 1 | 0 |
| Anish | B, W | 1 | 1 | 0 |

### Round 3

Standings: Magnus=1.5, Anish=1.5, Ian=1.5, Hikaru=0.5, Ding=0.5, Fabiano=0.5.

Score groups: {Magnus, Anish, Ian}, {Hikaru, Ding, Fabiano}.

The matching must avoid rematches (Magnus-Ding, Hikaru-Ian, Fabiano-Anish, Anish-Magnus, Ian-Hikaru, Ding-Fabiano from R1+R2):

| Board | White | Black | Rationale | Result |
|:-----:|-------|-------|-----------|:------:|
| 1 | Magnus (WB→W) | Ian (BW→B) | Same score, no rematch. Alternating colours. | ... |
| 2 | Anish (BW→W) | Hikaru (WB→B) | Cross-group, no rematch. Alternating. | ... |
| 3 | Fabiano (WB→W) | Ding (BW→B) | Cross-group, no rematch. Alternating. | ... |

All FIDE rules satisfied:
- R2: No rematches (all pairs are new)
- R5: Top score group paired first, then cross-group for remainder
- R6: All colour diffs are ±1 (within ±2 limit)
- R7: No three-in-a-row (patterns are WBW, WBB, BWB, BWW — all fine)
- R8: Every player alternates from their last colour

---

---

# Part III: Side-by-Side Comparison

## Rule Comparison

| Concern | Swiss System | Round Robin |
|---------|-------------|-------------|
| **Rematches** | Strictly forbidden (R2) with fallback only at round limits | Not applicable — every matchup occurs exactly once |
| **Bye (odd count)** | Dynamic per round: lowest-scored, non-previously-bye'd player (R3, R4) | Automatic: phantom rotation slot gives each player exactly 1 bye |
| **Score-based pairing** | Yes — same-score players are paired preferentially (R5) | No — all matchups are predetermined |
| **Colour balance** | Enforced: \|W−B\| ≤ 2 per player (R6) | Approximate: alternated by position formula |
| **Colour streaks** | Forbidden: no 3 same colours in a row (R7) | Not explicitly constrained (alternation formula handles most cases) |
| **Colour preference** | Active: fewer-played colour, else alternate from last (R8) | Passive: `(boardIndex + round) % 2` |
| **Algorithm type** | Constraint-satisfaction with backtracking | Deterministic rotation |
| **Computational cost** | O(n) typical, exponential worst case | O(n) always |

## Scoring Comparison

Both systems use the same point values:

| Result | Points for White | Points for Black |
|--------|:---:|:---:|
| White wins | 1 | 0 |
| Black wins | 0 | 1 |
| Draw | 0.5 | 0.5 |
| Bye | 1 | — |

## Tiebreaking

Both systems use **Buchholz tiebreak** — the sum of the scores of all opponents a player has faced. This rewards players who had tougher opposition.

```
Buchholz(player) = Σ score(opponent)
                   for each opponent the player faced
```

## Decision Flowchart: Which Format to Choose

```
                    How many players?
                          │
                    ┌─────┴─────┐
                    │           │
                ≤ 12        > 12
                    │           │
                    ▼           ▼
          ┌──────────────┐  ┌──────────────────┐
          │ How many     │  │ Swiss System      │
          │ rounds can   │  │ (recommended)     │
          │ you afford?  │  │                   │
          └──────┬───────┘  │ ⌈log₂(n)⌉ rounds │
                 │          │ is practical      │
           ┌─────┴─────┐   └──────────────────┘
           │           │
        n−1 or       < n−1
        more          rounds
           │           │
           ▼           ▼
  ┌──────────────┐  ┌──────────────────┐
  │ Round Robin  │  │ Swiss System     │
  │ (thorough)   │  │ (efficient)      │
  │              │  │                  │
  │ Every player │  │ Top players      │
  │ meets every  │  │ differentiated   │
  │ other player │  │ in fewer rounds  │
  └──────────────┘  └──────────────────┘
```

---

## Summary

### Swiss System

| Aspect | Approach |
|--------|----------|
| **Bye selection** | Priority-sorted candidates validated against matching feasibility |
| **Player matching** | Backtracking with score-proximity ordering and colour pre-check |
| **Colour assignment** | Global backtracking over all pairs with R8-guided search order |
| **Fallback strategy** | Three levels: full constraints → relaxed colour → allow rematches |
| **Testing** | ~900 randomised tournament simulations covering all FIDE rules |

### Round Robin

| Aspect | Approach |
|--------|----------|
| **Pairing method** | Circle/polygon rotation — fully deterministic |
| **Colour assignment** | Position-based alternation formula |
| **Bye handling** | Phantom player slot in rotation |
| **Total rounds** | n − 1 (even) or n (odd) — fixed and complete |
| **Complexity** | O(n) per round, no backtracking needed |
