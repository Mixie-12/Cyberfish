#!/bin/sh
# Abuse case 7 on Debian trixie, the card's release. The card's policy and
# registry configuration are installed with ghcr.io/mixie-12 pointed at a
# local registry and every trusted key replaced by a throwaway key of the
# same name; images are signed with the flags of the release workflow.
# skopeo checks each pull: Debian builds it and Podman from the same
# containers-image package, so it applies the policy as Podman on a device
# does, without needing a container runtime here. Distinct digests come from
# the platform images of one Alpine release.
#
# Runs as root in a throwaway container that shares the network of a
# registry on localhost:5000:
#   signatures.sh <repository checkout> <cosign binary>
set -eu

repo=$1
cosign=$2
registry=localhost:5000/dekoi
alpine=docker.io/library/alpine@sha256:3f4d6b32e26703c6082839234f43769d5046b3ac7c910033911d1e71d8cd630a
policy="$repo/device/host/etc/containers/policy.json"
work=$(mktemp -d)
export COSIGN_PASSWORD=throwaway DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq --no-install-recommends skopeo jq ca-certificates > /dev/null

keys=$(jq -r '[.transports.docker[][].keyPaths[]] | unique[] | ltrimstr("/etc/dekoi/") | rtrimstr(".pub")' "$policy")
repositories=$(jq -r '.transports.docker | keys[] | ltrimstr("ghcr.io/mixie-12/")' "$policy")
mkdir "$work/keys"
for key in $keys stranger; do
    "$cosign" generate-key-pair --output-key-prefix "$work/keys/$key" > /dev/null
done

# Copies the Alpine image for architecture $1 to $2 and, with $3, signs it with that key.
publish() {
    skopeo copy -q --override-arch "$1" --dest-tls-verify=false --digestfile "$work/digest" \
        "docker://$alpine" "docker://$registry/$2"
    if [ -n "${3-}" ]; then
        "$cosign" sign -y --key "$work/keys/$3.key" --allow-http-registry \
            --use-signing-config=false --new-bundle-format=false --tlog-upload=false \
            "$registry/${2%:*}@$(cat "$work/digest")"
    fi
}

set -- amd64 arm64 386 ppc64le s390x riscv64
accepted=""
for key in $keys; do
    for repository in $repositories; do
        publish "$1" "$repository:$key" "$key"
        accepted="$accepted $registry/$repository:$key"
    done
    shift
done
publish "$1" dekoi-agent:unsigned
publish "$2" dekoi-agent:stranger stranger
publish "$3" dekoi-agent-edge:edge "$(echo "$keys" | head -n 1)"
"$cosign" copy --allow-http-registry "$registry/dekoi-agent-edge:edge" "$registry/dekoi-ids:copied"
refused="$registry/dekoi-agent:unsigned $registry/dekoi-agent:stranger $registry/dekoi-agent-edge:edge
$registry/dekoi-ids:copied $alpine"

sed -e "s|ghcr.io/mixie-12|$registry|g" -e "s|/etc/dekoi/|$work/keys/|g" "$policy" > /etc/containers/policy.json
install -d /etc/containers/registries.d
sed -e "s|ghcr.io/mixie-12|$registry|" "$repo/device/host/etc/containers/registries.d/dekoi.yaml" \
    > /etc/containers/registries.d/dekoi.yaml

pull() { skopeo copy -q --src-tls-verify=false "docker://$1" "dir:$(mktemp -d)"; }
for image in $accepted; do
    pull "$image"
    echo "accepted $image"
done
for image in $refused; do
    if pull "$image" 2> "$work/error"; then
        echo "accepted $image without a trusted signature"
        exit 1
    fi
    grep -q "Source image rejected" "$work/error" || { cat "$work/error"; exit 1; }
    echo "refused $image"
done
