# Study A: what to say and give to the participant (NFR-15)

**Requirement (specification Section 8, NFR-15):** "A new team member can run the flagship demo from the README. Under 10 minutes, three commands."

## Who may take part
* Has **never** used CHRONOS, read its specification or watched a demonstration of it; has not helped write it.
* Can use a terminal (cd, run a command, read an error).
* JDK 21 and Maven 3.9+ are installed **before** the clock starts (the facilitator runs `tools/study-kit.sh env-check` on the participant's machine). Installing them is not part of the 10 minutes.
* Not the author, not the person who will grade anything. A classmate, a lab mate or a colleague who writes software is the intended person.

## Give the participant
* Only the folder `DIR/CHRONOS` made by `tools/study-kit.sh nfr15-prepare DIR` (the repository at a pinned commit, no `.git`, no build output).
* Nothing else: not this file, not `SUBMISSION_README.md`, no verbal help.

## Say exactly this (read it, do not paraphrase)
"Please run the flagship demonstration of this project, using the README in this folder. I will time you and I will not help or answer
questions about the project. Say aloud what you are doing and what confuses you. When you see the demonstration's final result, read it out to me."

## The task and the success criteria (from the specification, nothing added)
1. Start the stopwatch (`tools/study-kit.sh nfr15-clock DIR start`) when the participant first opens the README.
2. The participant follows the README. The README's quick start has three commands: select JDK 21 (`export JAVA_HOME=...`), `mvn -ntp clean package`, `java -jar chronos-cli/target/chronos.jar --data chronos-data demo`.
3. Stop the stopwatch (`... nfr15-clock DIR stop`) when the participant reads out "demonstration finished: 24 of 24 checks passed".
4. **PASS** = elapsed time under 600 s **and** at most three commands typed **and** the result was read out. Otherwise FAIL. Record the facts either way.

## Record (facilitator, while watching)
Commands typed (count every command the participant runs, including failed ones and `cd`), every hesitation over ten seconds, every error message, every README sentence the participant did not understand or re-read, anything the participant says aloud. Write them in `sheet.csv` (one row per participant) and keep the notes verbatim.

## Do not
Do not coach, do not point at the README, do not fix an environment problem during the clock (stop it, note it, restart the participant with a clean folder if the problem is the machine, and say so on the sheet). Do not drop a failed session from the results.
