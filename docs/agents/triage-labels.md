# Triage Labels

The five canonical triage roles and their GitHub label strings.

| Role | Label | Description |
|------|-------|-------------|
| Needs triage | `needs-triage` | Maintainer needs to evaluate |
| Needs info | `needs-info` | Waiting on reporter for more details |
| Ready for agent | `ready-for-agent` | Fully specified, an AFK agent can implement without human context |
| Ready for human | `ready-for-human` | Needs human implementation |
| Won't fix | `wontfix` | Will not be actioned |

## Extra labels

| Label | Purpose |
|-------|---------|
| `prd` | Marks a PRD issue |
| `slice` | Marks a vertical slice issue |

## Setup

These labels need to be created in the repo:
```
gh label create needs-triage --description "Needs evaluation" --color FBCA04
gh label create needs-info --description "Waiting on reporter" --color 0E8A16
gh label create ready-for-agent --description "AFK-ready, fully specified" --color 1D76DB
gh label create ready-for-human --description "Needs human implementation" --color B60205
gh label create wontfix --description "Will not be actioned" --color BDBDBD
gh label create prd --description "Product Requirements Document" --color C5DEF5
gh label create slice --description "Vertical slice" --color D4C5F9
```
