#!/bin/sh
set -eu

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

# Names and file paths are configuration; secret contents never enter arguments or logs.
load_secret() {
    secret_name=$1
    secret_current=$2
    secret_file=$3
    if [ -n "$secret_file" ]; then
        [ -z "$secret_current" ] || fail "Set only $secret_name or ${secret_name}_FILE."
        case "$secret_file" in /*) ;; *) fail "${secret_name}_FILE must be an absolute path." ;; esac
        [ -f "$secret_file" ] && [ -r "$secret_file" ] || fail "Cannot read ${secret_name}_FILE."
        secret_current=$(cat "$secret_file") || fail "Cannot load ${secret_name}_FILE."
    fi
    export "$secret_name=$secret_current"
    unset secret_current secret_file secret_name
}

require_value() {
    [ -n "$2" ] || fail "Required runtime setting $1 is missing."
}

profile=${SPRING_PROFILES_ACTIVE:-production}
case "$profile" in production|bootstrap-admin) ;; *) fail "Only production or bootstrap-admin is supported by this image." ;; esac
[ -z "${SPRING_PROFILES_INCLUDE:-}${SPRING_APPLICATION_JSON:-}${SPRING_CONFIG_NAME:-}${SPRING_CONFIG_LOCATION:-}${SPRING_CONFIG_ADDITIONAL_LOCATION:-}${SPRING_CONFIG_IMPORT:-}" ] \
    || fail "Additional Spring profiles and external configuration are not supported by this image."
case "${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-} ${_JAVA_OPTIONS:-}" in
    *spring.profiles*|*spring.config*|*spring.application.json*) fail "Spring profile/configuration overrides in Java options are not supported." ;;
esac
for argument in "$@"; do
    case "$argument" in
        --spring.profiles.*|--spring.config.*|--spring.application.json*|--server.forward-headers-strategy*|--server.tomcat.remoteip.*|--server.servlet.session.cookie.secure*)
            fail "Protected runtime settings must use the image's environment contract." ;;
    esac
done
[ "${ECE_KEYLOG:-}" != 1 ] || fail "ECE_KEYLOG is forbidden in this image."

load_secret APP_DB_PASSWORD "${APP_DB_PASSWORD:-}" "${APP_DB_PASSWORD_FILE:-}"
require_value APP_DB_URL "${APP_DB_URL:-}"
require_value APP_DB_USERNAME "${APP_DB_USERNAME:-}"
require_value APP_DB_PASSWORD "$APP_DB_PASSWORD"

if [ "$profile" = bootstrap-admin ]; then
    load_secret APP_BOOTSTRAP_LOGIN_ID "${APP_BOOTSTRAP_LOGIN_ID:-}" "${APP_BOOTSTRAP_LOGIN_ID_FILE:-}"
    load_secret APP_BOOTSTRAP_PASSWORD "${APP_BOOTSTRAP_PASSWORD:-}" "${APP_BOOTSTRAP_PASSWORD_FILE:-}"
    load_secret APP_BOOTSTRAP_DISPLAY_NAME "${APP_BOOTSTRAP_DISPLAY_NAME:-}" "${APP_BOOTSTRAP_DISPLAY_NAME_FILE:-}"
    require_value APP_BOOTSTRAP_LOGIN_ID "$APP_BOOTSTRAP_LOGIN_ID"
    require_value APP_BOOTSTRAP_PASSWORD "$APP_BOOTSTRAP_PASSWORD"
    require_value APP_BOOTSTRAP_DISPLAY_NAME "$APP_BOOTSTRAP_DISPLAY_NAME"
    exec java -jar /opt/app/app.jar --spring.profiles.active=bootstrap-admin "$@"
fi

load_secret APP_VAPID_PUBLIC_KEY "${APP_VAPID_PUBLIC_KEY:-}" "${APP_VAPID_PUBLIC_KEY_FILE:-}"
load_secret APP_VAPID_PRIVATE_KEY "${APP_VAPID_PRIVATE_KEY:-}" "${APP_VAPID_PRIVATE_KEY_FILE:-}"
load_secret APP_VAPID_SUBJECT "${APP_VAPID_SUBJECT:-}" "${APP_VAPID_SUBJECT_FILE:-}"
require_value APP_TRUSTED_PROXY_REGEX "${APP_TRUSTED_PROXY_REGEX:-}"
if [ "${JEONGEUM_PUSH_ENABLED:-false}" = true ]; then
    require_value APP_VAPID_PUBLIC_KEY "$APP_VAPID_PUBLIC_KEY"
    require_value APP_VAPID_PRIVATE_KEY "$APP_VAPID_PRIVATE_KEY"
    require_value APP_VAPID_SUBJECT "$APP_VAPID_SUBJECT"
fi
exec java -jar /opt/app/app.jar \
    --spring.profiles.active=production \
    --server.servlet.session.cookie.secure=true \
    --server.forward-headers-strategy=native \
    "--server.tomcat.remoteip.internal-proxies=$APP_TRUSTED_PROXY_REGEX" "$@"
