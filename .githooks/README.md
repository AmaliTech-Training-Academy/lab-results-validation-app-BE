# Git Hooks

Professional Git hooks for enforcing code standards and workflow policies.

## Quick Setup

**Windows:**
```bash
.githooks\setup.bat
```

**Unix/Linux/Mac:**
```bash
chmod +x .githooks/setup.sh
./.githooks/setup.sh
```

## Hooks Overview

### 1. commit-msg
Validates commit message format using conventional commits standard.

### 2. pre-commit
Validates branch naming conventions before allowing commits.

### 3. pre-push
Prevents direct pushes to protected branches (main, master, develop).

### 4. prepare-commit-msg
Automatically extracts and prepends ticket numbers from branch names.

## Commit Message Standard

### Format
```
<type>(scope): <subject>
```

### Rules
- Type is required and must be one of: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `hotfix`, `perf`, `ci`, `build`, `revert`
- Scope is optional and must be lowercase with hyphens
- Subject must start with lowercase letter
- Subject must be at least 10 characters
- Use imperative mood ("add" not "added" or "adds")
- Do not end with a period

### Types

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation changes |
| `style` | Code style changes (formatting, semicolons, etc.) |
| `refactor` | Code refactoring without changing functionality |
| `test` | Adding or updating tests |
| `chore` | Maintenance tasks, dependency updates |
| `hotfix` | Critical production fixes |
| `perf` | Performance improvements |
| `ci` | CI/CD configuration changes |
| `build` | Build system or external dependency changes |
| `revert` | Revert previous commit |

### Examples

```bash
# Good commits
feat: add user authentication module
fix(api): resolve null pointer exception in user service
chore(deps): update spring boot to version 3.2.0
hotfix: patch critical security vulnerability
perf(database): optimize query performance for user lookup

# Bad commits
Feat: Add feature          # Type should be lowercase
fix: bug                   # Subject too short
feat: Added new feature.   # Should use imperative mood, no period
update code                # Missing type
```

## Branch Naming Convention

### Format
```
<type>/<description>
```

### Rules
- Branch names must be lowercase
- Use hyphens to separate words
- Description should be concise and descriptive
- Can include ticket/issue numbers (e.g., `feature/PROJ-123-user-auth`)

### Valid Types

| Type | Description |
|------|-------------|
| `main` | Main production branch |
| `develop` | Development branch |
| `feature/` | New features |
| `feat/` | New features (short form) |
| `chore/` | Maintenance tasks |
| `hotfix/` | Critical production fixes |
| `fix/` | Bug fixes |
| `bugfix/` | Bug fixes (alternative) |

### Examples

```bash
# Good branch names
feature/user-authentication
feat/add-login-endpoint
chore/update-dependencies
hotfix/critical-security-patch
fix/null-pointer-exception
feature/PROJ-123-user-profile

# Bad branch names
Feature/UserAuth           # Should be lowercase
feature/user_auth          # Use hyphens, not underscores
my-branch                  # Missing type prefix
feature/                   # Missing description
```

## Protected Branches

Direct pushes to the following branches are blocked:
- `main`
- `master`
- `develop`

Use pull requests to merge changes into these branches.

### Bypass Protection (Not Recommended)
```bash
git push --no-verify
```

## Automatic Ticket Number Extraction

If your branch name includes a ticket number (e.g., `feature/PROJ-123-add-login`), the prepare-commit-msg hook will automatically prepend it to your commit message:

```bash
# Branch: feature/PROJ-123-add-login
# Your commit: feat: add login endpoint
# Final commit: [PROJ-123] feat: add login endpoint
```

## Troubleshooting

### Hooks not running
```bash
# Verify hooks path is configured
git config core.hooksPath

# Should output: .githooks
```

### Permission denied (Unix/Linux/Mac)
```bash
# Make hooks executable
chmod +x .githooks/*
```

### Disable hooks temporarily
```bash
# Skip hooks for a single commit
git commit --no-verify

# Skip hooks for a single push
git push --no-verify
```

## Best Practices

1. **Write clear commit messages** - Future you will thank present you
2. **Keep commits atomic** - One logical change per commit
3. **Use meaningful branch names** - Describe what you're working on
4. **Never bypass hooks without good reason** - They exist to maintain quality
5. **Include ticket numbers in branch names** - Automatic tracking is helpful
6. **Review your changes before committing** - Use `git diff --staged`

## Contributing

When adding new hooks:
1. Document the hook's purpose
2. Provide clear error messages
3. Include examples of valid/invalid inputs
4. Update this README
5. Test on both Unix and Windows systems
