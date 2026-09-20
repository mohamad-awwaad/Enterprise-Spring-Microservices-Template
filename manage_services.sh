#!/bin/bash

# Define colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

COMPOSE_FILE="compose.yaml"

# Management (actuator) ports - separate from each service's public port.
# See MANAGEMENT_PORT in .env.example.
declare -A MGMT_PORTS=(
    [bff]=9081
    [gateway]=9888
    [profile-service]=9082
    [order-service]=9083
    [keycloak-admin-service]=9084
)

declare -A APP_PORTS=(
    [bff]=8081
    [gateway]=8888
    [profile-service]=8082
    [order-service]=8083
    [keycloak-admin-service]=8084
)

start_services() {
    echo -e "${YELLOW}Starting SEC Microservices Environment...${NC}"

    # ==========================================
    # 1. Start infra (Keycloak, Postgres, Redis, observability stack)
    # ==========================================
    echo -e "\n${YELLOW}[1/2] Starting infra (docker compose up -d --wait)...${NC}"
    # --wait blocks until every infra container reports healthy, replacing the
    # old fixed "sleep 10" guess.
    docker compose -f "$COMPOSE_FILE" up -d --wait
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Infra is up and healthy.${NC}"
    else
        echo -e "${RED}Failed to start infra. Run 'docker compose -f $COMPOSE_FILE ps' to see what's unhealthy.${NC}"
        exit 1
    fi

    start_apps
}

start_apps() {
    # ==========================================
    # 2. Start Spring Boot Services (on the host, via the Maven wrapper)
    # ==========================================
    start_service_app() {
        SERVICE_DIR=$1
        NAME=$2
        PORT=${APP_PORTS[$SERVICE_DIR]}
        MGMT_PORT=${MGMT_PORTS[$SERVICE_DIR]}

        echo -e "\n${YELLOW}[Checking $NAME]${NC}"

        # Check if port is in use
        if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null ; then
            echo -e "${GREEN}$NAME is already running on port $PORT.${NC}"
        else
            echo -e "${YELLOW}Starting $NAME on port $PORT (management port $MGMT_PORT)...${NC}"
            # Run in background with nohup, redirect output to a log file
            (nohup ./mvnw -pl "$SERVICE_DIR" -am spring-boot:run > "$SERVICE_DIR/run.log" 2>&1 &)

            # Poll the readiness probe instead of guessing with a fixed sleep.
            for i in $(seq 1 30); do
                if curl -sf "http://localhost:$MGMT_PORT/actuator/health/readiness" >/dev/null 2>&1; then
                    echo -e "${GREEN}$NAME is up and ready.${NC}"
                    break
                fi
                sleep 2
                if [ "$i" -eq 30 ]; then
                    echo -e "${YELLOW}$NAME hasn't reported ready yet. Check logs at $SERVICE_DIR/run.log${NC}"
                fi
            done
        fi
    }

    start_service_app "bff" "BFF Service"
    start_service_app "gateway" "Gateway Service"
    start_service_app "profile-service" "Profile Service"
    start_service_app "order-service" "Order Service"
    start_service_app "keycloak-admin-service" "Keycloak Admin Service"

    echo -e "\n${GREEN}All services have been processed.${NC}"
}

stop_services() {
    echo -e "${YELLOW}Stopping SEC Microservices Environment...${NC}"

    stop_apps

    echo -e "\n${YELLOW}Stopping infra...${NC}"
    docker compose -f "$COMPOSE_FILE" stop
    echo -e "${GREEN}Infra stopped.${NC}"

    echo -e "\n${GREEN}All services stopped.${NC}"
}

stop_apps() {
    stop_process_on_port() {
        PORT=$1
        NAME=$2
        PID=$(lsof -Pi :$PORT -sTCP:LISTEN -t)
        if [ -n "$PID" ]; then
            echo -e "${YELLOW}Stopping $NAME (PID: $PID)...${NC}"
            kill $PID
            echo -e "${GREEN}$NAME stopped.${NC}"
        else
            echo -e "${GREEN}$NAME is not running.${NC}"
        fi
    }

    # Stop Spring Boot Apps
    stop_process_on_port 8081 "BFF Service"
    stop_process_on_port 8888 "Gateway Service"
    stop_process_on_port 8082 "Profile Service"
    stop_process_on_port 8083 "Order Service"
    stop_process_on_port 8084 "Keycloak Admin Service"
}

test_services() {
    echo -e "${YELLOW}Running tests for all services...${NC}"
    echo -e "${YELLOW}(Tests tagged @Tag(\"requires-keycloak\") need a live Keycloak at localhost:8080 -"
    echo -e "run './manage_services.sh start' first, or pass -Dtest.excludedGroups=requires-keycloak to skip them.)${NC}"

    run_test() {
        SERVICE_DIR=$1
        NAME=$2
        echo -e "\n${YELLOW}[Testing $NAME]${NC}"
        ./mvnw -pl "$SERVICE_DIR" -am test
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}$NAME tests passed.${NC}"
        else
            echo -e "${RED}$NAME tests failed.${NC}"
            exit 1
        fi
    }

    run_test "bff" "BFF Service"
    run_test "gateway" "Gateway Service"
    run_test "profile-service" "Profile Service"
    run_test "order-service" "Order Service"
    run_test "keycloak-admin-service" "Keycloak Admin Service"

    echo -e "\n${GREEN}All tests passed successfully!${NC}"
}

# Main Execution Logic
case "$1" in
    start|"")
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart)
        stop_services
        sleep 2
        start_services
        ;;
    restart-apps)
        stop_apps
        sleep 2
        start_apps
        ;;
    test)
        test_services
        ;;
    *)
        echo -e "${RED}Usage: $0 {start|stop|restart|restart-apps|test}${NC}"
        exit 1
        ;;
esac
