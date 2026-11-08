# Input: a pull request's files (GitHub's pulls/{n}/files). $author: its
# author. Output: what it changes that auto-merge.yml may not merge, empty
# when nothing. Dependabot may change dependency manifests and lockfiles;
# where those also hold commands (workflows, Containerfiles), only the lines
# that name a dependency. This repository's own automation may change the
# pins it proposes (pins.yml, installer.yml).

def rules:
  if $author == "dependabot[bot]" then [
    {path: "^\\.github/(workflows/[^/]+\\.yml|actions/[^/]+/action\\.yml)$",
     lines: "^\\s*(- )?uses: [^\\s@]+@[0-9a-f]{40}( # \\S+)?$"},
    {path: "^device/[a-z]+/Containerfile$", lines: "^FROM (--platform=\\S+ )?\\S+( AS \\S+)?$"},
    {path: "^clients/gradle/libs\\.versions\\.toml$"},
    {path: "^clients/gradle/wrapper/gradle-wrapper\\.(jar|properties)$"},
    {path: "^clients/gradlew(\\.bat)?$"},
    {path: "^device/canary/requirements\\.(in|txt)$"}]
  elif $author == "github-actions[bot]" then [
    {path: "^device/(image|installer)\\.json$"},
    {path: "^device/installer/Containerfile$"}]
  else [] end;

def changed_lines: .patch | split("\n") | map(select(test("^[-+]")) | .[1:]);

rules as $rules
| .[]
| . as $file
| (first($rules[] | select(. as $rule | $file.filename | test($rule.path))) // null) as $rule
| if .status != "modified" then "\(.filename) is \(.status)"
  elif $rule == null then "\(.filename) is not a file it may change"
  elif $rule.lines == null then empty
  elif .patch == null then "\(.filename) has no diff to check"
  elif any(changed_lines[]; test($rule.lines) | not) then "\(.filename) changes more than dependency lines"
  else empty end
