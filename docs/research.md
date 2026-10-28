# Why Muse Board works the way it does

Short notes on the research behind the product, gathered in October 2026.
Effect sizes come mostly from abstracts and university summaries, so treat
exact numbers as approximate. Each row says what we built from it.

| Finding | Evidence | In the product |
|---|---|---|
| Procrastination is mostly short-term mood repair: avoiding the bad feeling of starting. | Sirois & Pychyl 2013, *Social and Personality Psychology Compass* | The widget shows a tiny first step, never the whole project. |
| Task aversiveness, low self-efficacy and impulsiveness are the strongest predictors. | Steel 2007, *Psychological Bulletin*, 691 correlations | Small steps make the task less aversive and build confidence. |
| Close sub-goals beat distant goals for persistence and confidence. | Bandura & Schunk 1981, *JPSP* | `first_step`, and re-writing it as steps get done. |
| "When X, where Y, I will Z" plans (implementation intentions) have a medium-to-large effect, d = 0.65. | Gollwitzer & Sheeran 2006 (94 tests); 2024 update with 642 tests | `plan_at` + `plan_where`; one reminder at that time; Later asks *when*. |
| Monitoring progress helps (d = 0.40), more when it is recorded. | Harkin et al. 2016, *Psychological Bulletin*, 138 RCTs | Done count on the widget and the web app. |
| Seeing a broken streak lowers motivation; being able to repair it softens that. | Silverman & Barasch 2023, *Journal of Consumer Research* | No streaks. Counts reset daily and never shame. |
| Forgiving yourself for procrastinating predicted less of it next time. | Wohl, Pychyl & Bennett 2010 (correlational) | Neutral copy. The old "Still waiting on you" line is gone. |
| Self-set deadlines: the famous result is retracted. | Ariely & Wertenbroch 2002, retracted Sep 2026 after a failed replication (Hyndman & Bisin, *Psychological Science* 2026) | We don't build on deadlines. `due_at` is shown only when there is no plan. |
| Reminders help (69% vs 60% of days in one study) but frequent alerts get habituated; timing matters (receptivity up to +36%). | Smartphone reminder study (PMC11192903); JITAI receptivity work (ETH / Dartmouth) | Exactly one notification per plan, at the planned time. |
| CBT-style approaches have the best evidence; for adult ADHD, CBT built on a calendar, a task list and breaking tasks down doubled responders (53% vs 23%). | van Eerde & Klingsieck 2018; Rozental 2015; Safren et al. 2010, *JAMA* | The app is those three tools: one list, planned times, broken-down steps. |
| ADHD comes with a strong preference for immediate reward. | Sonuga-Barke (delay aversion line of work) | Done updates the widget instantly from the cache, with a feedback line. |
| Fixed breaks gave less fatigue than self-chosen ones, same output; later work mixed. | Biwer et al. 2023, *BJEP* | Not built yet: a "start 10 minutes" timer is a candidate. |
| Temptation bundling raised gym visits 10 to 14% in a large follow-up. | Milkman et al. | Not built. |
| Body doubling has thin, mixed evidence. | arXiv 2509.12153 and reviews | Not built. |

Standards checked: Android widget quality tiers (developer.android.com,
"Widget Quality Tiers") and WCAG 2.2 (4.5:1 text contrast, 24px minimum
targets; we use 44px and up).

## Not doing, on purpose

Streaks you can lose, points or badges, more alarms, and self-set deadlines
as the main lever.
