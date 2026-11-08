# Input: a workflow's on: map. Arguments: $event, $kind (branches or tags),
# $name (the branch or tag), $paths (changed paths; empty for every path).
# Output: true when GitHub starts the workflow. GitHub's filter patterns:
# * does not match /, ** matches anything, **/ also no directory. Path
# filters do not apply to tags.

def regex:
  if test("^[A-Za-z0-9._/*-]+$") | not then error("unsupported filter pattern: \(.)") else . end
  | "^" + (split("**/") | map(split("**") | map(split("*") | map(gsub("\\."; "\\.")) | join("[^/]*"))
    | join(".*")) | join("(.*/)?")) + "$";

def matches($patterns): . as $value | any($patterns[]; . as $p | $value | test($p | regex));

if type != "object" then error("on: is not a map")
elif has($event) | not then false
else (.[$event] // {}) as $filter
  | if $filter | has("branches-ignore") or has("tags-ignore") or has("paths-ignore")
    then error("*-ignore filters are not supported") else . end
  # Every pattern is checked, not only those tried before a match.
  | ([$filter.branches, $filter.tags, $filter.paths] | map(. // []) | add | map(regex)) as $checked
  | ($name | matches($filter[$kind] // (if $filter | has("branches") or has("tags") then [] else ["**"] end)))
    and ($kind == "tags" or ($paths | length) == 0 or any($paths[]; matches($filter.paths // ["**"])))
end
