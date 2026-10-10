Inputs a fuzzer crashed on. Each is replayed on every build by `FuzzCorpusTest`.
Add the file the fuzzer wrote under `app/build/fuzz/*/crashes/`, once the bug is fixed, named for what it was (`json-nesting-100k.json`, not `crash-3fa9...`). See docs/development.md.
