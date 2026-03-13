# Speedrun Bot Base

This project is a Fabric client mod scaffold for building a Minecraft Java speedrunning bot.

## What is here

- A Fabric Gradle setup targeting Minecraft 1.21.11.
- A client entrypoint with two keybinds.
- A per-tick bot controller.
- A small task interface so we can add navigation, combat, crafting, and routing logic without turning the codebase into one class.
- A reusable player action controller for forward, strafe, jump, sprint, sneak, attack, and use inputs.
- Four starter tasks:
  - `AutoMineNearestLogTask` searches nearby logs, moves into range, and attempts to mine one.
  - `MoveForwardTask` holds forward and sprint, performs periodic jumps, and slightly steers the camera.
  - `LookAroundTask` rotates the camera to prove the control loop works.
  - `StatusOverlayTask` shows live player state in the action bar.

## Controls

- `B`: toggle the bot on or off.
- `N`: switch to the next task.

The tasks cycle in this order:

1. `Move Forward`
2. `Look Around`
3. `Status Overlay`

Current internal cycle order in code:

1. `Mine Nearest Log`
2. `Move Forward`
3. `Look Around`
4. `Status Overlay`

## Checking It In Client

You need a Java 21 JDK and a way to run Gradle.

1. Install Java 21.
2. Install Gradle, or generate a Gradle wrapper from a machine that already has Gradle.
3. In the project root, run `gradle runClient`.
4. When the dev client opens, create or enter a world.
5. Press `B` to toggle the bot.
6. Press `N` to switch between tasks.

What you should see:

- On `Mine Nearest Log`, the bot should pick a nearby tree log, walk toward it, and mine when in reach.
- On `Move Forward`, the player should walk forward, sprint, and jump every second.
- On `Look Around`, the player should rotate their view.
- On `Status Overlay`, the action bar should show position, health, and food.

If you want to test outside the Gradle dev client later, build the mod jar and place it in your Fabric `mods` folder.

## Next milestones

1. Add a world-query layer for blocks, structures, and nearby entities.
2. Add a planner that can sequence objectives like wood, stone, iron, lava pool, fortress, and stronghold.
3. Add block targeting, mining, and interact logic on top of the action controller.
4. Add logging and replay-friendly run metrics.

## Build

Use a Java 21 JDK. If Gradle is installed, you can generate a wrapper with `gradle wrapper`, then build with `gradlew build`.
