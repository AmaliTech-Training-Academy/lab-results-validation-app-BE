#!/bin/sh
# Git Hooks Setup Script
# Configures Git to use custom hooks from .githooks directory

set -e

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo "${BLUE}║              Git Hooks Configuration                       ║${NC}"
echo "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Configure Git to use .githooks directory
echo "${YELLOW}Configuring Git hooks path...${NC}"
git config core.hooksPath .githooks
echo "${GREEN}✓${NC} Git hooks path configured"

# Make hooks executable
echo "${YELLOW}Making hooks executable...${NC}"
chmod +x .githooks/commit-msg
chmod +x .githooks/pre-commit
chmod +x .githooks/pre-push
chmod +x .githooks/prepare-commit-msg
echo "${GREEN}✓${NC} Hooks are now executable"

echo ""
echo "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo "${GREEN}║              Git Hooks Configured Successfully             ║${NC}"
echo "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "${BLUE}Enabled hooks:${NC}"
echo "  ${GREEN}✓${NC} commit-msg      - Validates commit message format"
echo "  ${GREEN}✓${NC} pre-commit      - Validates branch names"
echo "  ${GREEN}✓${NC} pre-push        - Prevents direct push to protected branches"
echo "  ${GREEN}✓${NC} prepare-commit-msg - Auto-adds ticket numbers"
echo ""
echo "${YELLOW}Commit format:${NC} <type>(scope): <subject>"
echo "${YELLOW}Valid types:${NC} feat, fix, docs, style, refactor, test, chore, hotfix, perf, ci, build, revert"
echo ""
echo "${YELLOW}Branch format:${NC} <type>/<description>"
echo "${YELLOW}Valid types:${NC} feature, feat, chore, hotfix, fix, bugfix"
echo ""
echo "${YELLOW}Protected branches:${NC} main, master, develop (require PR)"
echo ""
