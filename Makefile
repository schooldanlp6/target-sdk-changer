# Thin wrapper around build.py.
#   make                    module for the default board (generic-8)
#   make BOARD=xa1-8        module for one board
#   make BOARD=generic-8 MAXSDK=11 EXPERIMENTAL=true    (false | true | all)
#   make boards             list boards
#   make all-boards         every supported board once (its defaults)
#   make matrix             every buildable board x every target (8.1 9 10 11) x false/true/all
#   make matrix TARGETS="10 11"
#   make init               create imported/ build/ out/, build vdexExtractor (phone + PC)
#   make new-board NAME=myphone VERSION=8.0
#                           create imported/myphone/version.txt for a custom board
#   make clean              delete build/ and out/

BOARD ?= generic-8
MAXSDK ?=
EXPERIMENTAL ?= false
PY ?= python3

ARGS = --board $(BOARD) --experimental $(EXPERIMENTAL) $(if $(MAXSDK),--maxSDK $(MAXSDK))

.PHONY: module boards all-boards matrix init new-board clean
module:
	$(PY) build.py $(ARGS)

boards:
	$(PY) build.py --list-boards

all-boards:
	@for b in $$($(PY) build.py --list-boards | awk '/\[supported\]/ {print $$1}'); do \
	  $(PY) build.py --board $$b || exit 1; done

TARGETS ?=
matrix:
	tools/build-matrix.sh $(TARGETS)

init:
	mkdir -p imported build out
	@[ -f imported/README.txt ] || printf '%s\n' \
	  'Custom boards: one folder per board, e.g. imported/myphone/' \
	  '  version.txt   Android version of the phone, e.g. 8.0 (one line)' \
	  '  *.jar *.vdex  framework files copied from the phone (see README "Boards")' \
	  'Create one with: make new-board NAME=myphone VERSION=8.0' > imported/README.txt
	@if [ -x tools/bin/host/vdexExtractor ] && [ -x tools/bin/arm64-v8a/vdexExtractor ]; then \
	  echo "vdexExtractor already built"; else tools/build-vdexextractor.sh; fi
	@ls tools/aosp/*.jar >/dev/null 2>&1 || echo "note: no AOSP reference jars in tools/aosp/ (see tools/aosp/README.md)"

new-board:
	@[ -n "$(NAME)" ] && [ -n "$(VERSION)" ] || { echo "usage: make new-board NAME=myphone VERSION=8.0"; exit 1; }
	@[ ! -e boards/$(NAME).prop ] || { echo "$(NAME) is an official board (boards/$(NAME).prop)"; exit 1; }
	mkdir -p imported/$(NAME)
	@[ ! -e imported/$(NAME)/version.txt ] || { echo "imported/$(NAME)/version.txt exists, left as is"; exit 0; }
	echo "$(VERSION)" > imported/$(NAME)/version.txt
	@echo "now copy the phone's framework files into imported/$(NAME)/ (README \"Boards\")"

clean:
	rm -rf build out
