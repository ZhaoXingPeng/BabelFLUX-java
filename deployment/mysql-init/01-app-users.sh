#!/bin/sh
set -eu

require_value() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "$name must be set" >&2
        exit 1
    fi
}

require_safe_identifier() {
    case "$1" in
        *[!A-Za-z0-9_]*|'')
            echo "MYSQL_DATABASE must contain only letters, digits, and underscores" >&2
            exit 1
            ;;
    esac
}

require_safe_password() {
    case "$1" in
        *[!A-Za-z0-9]*|'')
            echo "MySQL app and migrator passwords must be alphanumeric; use openssl rand -hex 32" >&2
            exit 1
            ;;
    esac
}

require_value MYSQL_DATABASE
require_value MYSQL_ROOT_PASSWORD
require_value MYSQL_APP_PASSWORD
require_value MYSQL_MIGRATOR_PASSWORD
require_safe_identifier "$MYSQL_DATABASE"
require_safe_password "$MYSQL_APP_PASSWORD"
require_safe_password "$MYSQL_MIGRATOR_PASSWORD"

export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql --protocol=socket -uroot --database="$MYSQL_DATABASE" <<SQL
CREATE USER IF NOT EXISTS 'babelflux_app'@'%' IDENTIFIED BY '$MYSQL_APP_PASSWORD';
ALTER USER 'babelflux_app'@'%' IDENTIFIED BY '$MYSQL_APP_PASSWORD';
CREATE USER IF NOT EXISTS 'babelflux_migrator'@'%' IDENTIFIED BY '$MYSQL_MIGRATOR_PASSWORD';
ALTER USER 'babelflux_migrator'@'%' IDENTIFIED BY '$MYSQL_MIGRATOR_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$MYSQL_DATABASE\`.* TO 'babelflux_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`$MYSQL_DATABASE\`.* TO 'babelflux_migrator'@'%';
FLUSH PRIVILEGES;
SQL
unset MYSQL_PWD
