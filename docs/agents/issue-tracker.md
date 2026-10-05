# Issue tracker: GitHub

Issues and specs live in GitHub Issues for medlor/portfolio-performance-pal.
Use the gh CLI with --repo medlor/portfolio-performance-pal.

## Conventions

- Create: gh issue create --repo medlor/portfolio-performance-pal --title "..." --body-file <file>
- Read: gh issue view <number> --repo medlor/portfolio-performance-pal --comments
- List: gh issue list --repo medlor/portfolio-performance-pal --state open --json number,title,body,labels
- Comment: gh issue comment <number> --repo medlor/portfolio-performance-pal --body-file <file>
- Apply/remove labels: gh issue edit <number> --repo medlor/portfolio-performance-pal --add-label "..." / --remove-label "..."
- Close: gh issue close <number> --repo medlor/portfolio-performance-pal

For multiline bodies, write the exact Markdown to a file and use --body-file.
Read docs/agents/triage-labels.md for label mappings.

## Pull requests as a triage surface

PRs as a request surface: no.

## Skill operations

"Publish to the issue tracker" means create a GitHub issue.
"Fetch the relevant ticket" means read the issue and its comments.
