# Elemental SMP

A Paper 1.21.11 plugin with four assigned elements, survival passives, PvP abilities, combos, scroll items and admin commands.

## Requirements
- Paper 1.21.11
- Java 21

## Build
```bash
mvn clean package
```
The JAR is `target/ElementalSMP.jar`. GitHub Actions also builds it and uploads an `ElementalSMP` artifact.

## Player commands
- `/element` — view your element
- `/element skills` — list techniques
- `/element mastery` — view mastery
- `/element scrolls` — scroll help

## Casting
Sneak + right-click to cast the ability mapped to the held hotbar slot:
1. Ember/Stone/Water/Gust shot
2. Dash
3. Sweep
4. Defensive or utility skill
5. Ultimate (requires the matching ultimate scroll)

Ability cooldowns and passive settings are in `config.yml`.

## Elements
- **Fire:** combat burst and automatic smelting of eligible raw ore drops.
- **Earth:** mining-speed passive and terrain control.
- **Water:** faster swimming and crowd-control abilities.
- **Air:** double jump, reduced fall damage, and high mobility.

## Admin commands
- `/elementadmin assign <player> <fire|earth|water|air>`
- `/elementadmin reset <player>`
- `/elementadmin scroll <player> <element|ultimate>`
- `/elementadmin reload`

Data is stored in `plugins/ElementalSMP/players.db`. Back up this file before deleting or editing player data.

## Current MVP notes
This is the first playable foundation. Combat moves use server-side Bukkit/Paper mechanics and particles; scrolls are custom items. More advanced visual effects, structure-specific loot tables and deeper mastery trees can be added in follow-up versions.
