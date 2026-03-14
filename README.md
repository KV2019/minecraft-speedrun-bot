# Speedrun Bot Base

This project is a Fabric client mod scaffold for building a Minecraft Java speedrunning bot.

## What is here

- A Fabric Gradle setup targeting Minecraft 1.21.11.
- A client entrypoint with two keybinds.
- A per-tick bot controller with a single active task.
- A small task interface so we can add navigation, combat, crafting, and routing logic without turning the codebase into one class.
- A reusable `PlayerActionController` for forward, strafe, jump, sprint, sneak, attack, and use inputs.
- An A\* `Navigator` that finds walkable paths around obstacles and detects when the player is stuck.
- One production task:
  - `AutoMineNearestLogTask` – finds the nearest accessible log, navigates to it using A\* pathfinding, clears any obstructing leaves, and mines the log. Repeats until no more logs are nearby, then finishes.
- Three utility/test tasks kept for development:
  - `MoveForwardTask` – holds forward and sprint, performs periodic jumps, and slightly steers the camera.
  - `LookAroundTask` – rotates the camera to confirm the control loop works.
  - `StatusOverlayTask` – shows live player state (XYZ, HP, food) in the action bar.

## Controls

- `B`: toggle the bot on or off.
- `N`: restart the current task from scratch.

## Active task

The bot currently runs only `AutoMineNearestLogTask`. Pressing `N` restarts it so you can immediately test a fresh run without toggling the bot off and on.

## How the navigator works

`Navigator` runs an A\* search each time a new path is needed (at most every 10 ticks).
It only considers positions where the player can stand (two clear blocks above a solid floor),
treats leaf blocks as passable, and falls back to direct movement when no path is found.
A stuck-detection timer resets the path if the player stops making progress for 35 ticks.

## Checking It In Client

You need a Java 21 JDK and a way to run Gradle.

1. Install Java 21.
2. In the project root, run `./gradlew runClient` (Linux/macOS) or `gradlew.bat runClient` (Windows).
3. When the dev client opens, create or enter a world near some trees.
4. Press `B` to start the bot.
5. Watch the bot locate a log, navigate to it, and mine it.

What you should see:

- The action bar prints target coordinates when a log is acquired and when it is mined.
- The bot walks around obstacles rather than walking straight into them.
- After all nearby logs are collected the task finishes and the action bar shows "Task complete".

Press `N` to restart the task at any point.

If you want to test outside the Gradle dev client, build the mod jar and drop it in your Fabric `mods` folder.

## Next milestones

1. Add a multi-objective planner that sequences wood → crafting table → tools → stone → iron → lava pool → fortress → stronghold.
2. Add crafting and smelting task logic on top of the action controller.
3. Add combat and mob-avoidance logic.
4. Add logging and replay-friendly run metrics.

## Build

Use a Java 21 JDK.

```
./gradlew build
```

## One Command Client Restart

To kill the previous workspace dev client and start a fresh one:

PowerShell:

```
.\scripts\run-client.ps1
```

If you want to skip the build step and relaunch faster:

```
.\scripts\run-client.ps1 -SkipBuild
```
