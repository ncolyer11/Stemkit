# Sibling trees

Warped and crimson fungi grow with almost the same code. If you bone meal a warped fungus and a crimson fungus while the game's random generator is in the same state, the two trees start out the same: same height, same hat width, same layers. They only drift apart when crimson draws one extra random number that warped doesn't. That happens when crimson places certain extra hat blocks and when one of its vines actually starts hanging.

So a roll can end up in one of three places:

- **Different.** The trees split early and look nothing alike. This is nearly every roll.
- **Sibling (same shape).** Both trees fill exactly the same blocks, stem and hat, but some of the wart and shroomlights can differ. Vines are ignored.
- **Identical sibling.** Same shape, and also wart and shroomlights in exactly the same places. The only thing that changes is which block types they're made of.

Identical siblings are rare, roughly 1 in 10,000 rolls. Shape-only siblings are about 100 times rarer than that again.

Try one in game:

```
/stemkit sibling 59438334661301
```

That grows the roll as warped on the left and crimson on the right and tells you which of the three it is. The catalogue has a few of the best ones (`sibling_most_glow`, `sibling_biggest`, `sibling_glow_ratio`), and [RESEARCH.md](RESEARCH.md#sibling-trees) covers how they were found.
