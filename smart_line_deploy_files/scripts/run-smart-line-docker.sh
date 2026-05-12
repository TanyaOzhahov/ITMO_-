#!/bin/bash

# Smart Line Widget Lake - Complete Docker Stack Runner
# Builds and starts all services in the correct order

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.smart-line.yml"
PROJECT_NAME="adapstory-smart-line"

# Functions
print_header() {
    echo ""
    echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC} $1"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

check_prerequisites() {
    print_header "Checking Prerequisites"
    
    # Check Docker
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed"
        exit 1
    fi
    print_success "Docker $(docker --version | cut -d' ' -f3)"
    
    # Check Docker Compose
    if ! command -v docker-compose &> /dev/null; then
        print_error "Docker Compose is not installed"
        exit 1
    fi
    print_success "Docker Compose $(docker-compose --version | cut -d' ' -f3)"
    
    # Check if ports are available
    print_info "Checking port availability..."
    
    local ports=(5432 6379 2181 9092 8080 8090)
    for port in "${ports[@]}"; do
        if netstat -tuln 2>/dev/null | grep -q ":$port "; then
            print_warning "Port $port is already in use"
        else
            print_success "Port $port is available"
        fi
    done
    
    # Check if docker-compose.smart-line.yml exists
    if [ ! -f "$COMPOSE_FILE" ]; then
        print_error "File not found: $COMPOSE_FILE"
        exit 1
    fi
    print_success "Found $COMPOSE_FILE"
}

build_docker_image() {
    print_header "Building Docker Image"
    
    print_info "Building adapstory-personalization-runtime..."
    
    if bash scripts/build-smart-line.sh; then
        print_success "Docker image built successfully"
    else
        print_error "Docker build failed"
        exit 1
    fi
}

start_services() {
    print_header "Starting Services"
    
    print_info "Starting containers (this may take 30-60 seconds)..."
    
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" up -d
    
    if [ $? -eq 0 ]; then
        print_success "Containers started"
    else
        print_error "Failed to start containers"
        exit 1
    fi
}

wait_for_services() {
    print_header "Waiting for Services to be Ready"
    
    local max_attempts=60
    local attempt=0
    local services=("postgres" "redis" "kafka" "personalization-runtime")
    
    print_info "Waiting for services to become healthy (max 5 minutes)..."
    
    while [ $attempt -lt $max_attempts ]; do
        local all_healthy=true
        
        for service in "${services[@]}"; do
            local status=$(docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" ps "$service" 2>/dev/null | grep -c "healthy" || echo "0")
            
            if [ "$status" -eq 0 ]; then
                all_healthy=false
                echo -ne "\r[$(($attempt + 1))/$max_attempts] Waiting for $service..."
            fi
        done
        
        if [ "$all_healthy" = true ]; then
            echo ""
            print_success "All services are healthy!"
            break
        fi
        
        sleep 5
        attempt=$((attempt + 1))
    done
    
    if [ $attempt -eq $max_attempts ]; then
        print_warning "Services may still be starting. Check logs with: docker-compose logs -f"
    fi
}

show_endpoints() {
    print_header "Service Endpoints"
    
    echo -e "${GREEN}Render Widget Endpoint:${NC}"
    echo "  POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/{tenantId}/render"
    echo ""
    
    echo -e "${GREEN}Submit Answer Endpoint:${NC}"
    echo "  POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/{tenantId}/submit-answer"
    echo ""
    
    echo -e "${GREEN}Health Check:${NC}"
    echo "  GET http://localhost:8080/actuator/health"
    echo ""
    
    echo -e "${GREEN}PostgreSQL Database:${NC}"
    echo "  Host: localhost:5432"
    echo "  User: adapstory"
    echo "  Password: adapstory-local"
    echo "  Database: adapstory"
    echo ""
    
    echo -e "${GREEN}Redis Cache:${NC}"
    echo "  Host: localhost:6379"
    echo ""
    
    echo -e "${GREEN}Kafka Topics:${NC}"
    echo "  smart-line.widget.rendered"
    echo "  smart-line.student.answered"
    echo "  smart-line.student.mastered"
    echo "  smart-line.session.terminated"
    echo ""
    
    echo -e "${GREEN}Kafka UI (Monitoring):${NC}"
    echo "  http://localhost:8090"
}

show_helpful_commands() {
    print_header "Helpful Commands"
    
    echo -e "${BLUE}View logs:${NC}"
    echo "  docker-compose -f $COMPOSE_FILE logs -f personalization-runtime"
    echo "  docker-compose -f $COMPOSE_FILE logs -f redis"
    echo "  docker-compose -f $COMPOSE_FILE logs -f postgres"
    echo ""
    
    echo -e "${BLUE}Run tests:${NC}"
    echo "  docker-compose -f $COMPOSE_FILE exec personalization-runtime mvn test -Dtest=SmartLine*"
    echo ""
    
    echo -e "${BLUE}Connect to services:${NC}"
    echo "  # PostgreSQL"
    echo "  docker exec -it adapstory-postgres-smart-line psql -U adapstory"
    echo ""
    echo "  # Redis"
    echo "  docker exec -it adapstory-redis-smart-line redis-cli"
    echo ""
    echo "  # Kafka"
    echo "  docker exec -it adapstory-kafka-smart-line kafka-console-consumer --bootstrap-server localhost:9092 --topic smart-line.widget.rendered --from-beginning"
    echo ""
    
    echo -e "${BLUE}Stop services:${NC}"
    echo "  docker-compose -f $COMPOSE_FILE down"
    echo ""
    
    echo -e "${BLUE}Clean up (remove volumes):${NC}"
    echo "  docker-compose -f $COMPOSE_FILE down -v"
}

test_endpoint() {
    print_header "Testing Smart Line Endpoint"
    
    print_info "Testing GET /actuator/health..."
    
    local response=$(curl -s -w "\n%{http_code}" http://localhost:8080/actuator/health)
    local http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" -eq 200 ]; then
        print_success "Service is responding (HTTP 200)"
        echo "$response" | head -n-1
    else
        print_warning "Service returned HTTP $http_code"
        print_warning "This may be normal if services are still starting"
    fi
    
    print_info "Testing POST /smart-line/{tenantId}/render..."
    
    local render_response=$(curl -s -X POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/00000000-0000-0000-0000-000000000001/render \
        -H "Content-Type: application/json" \
        -d '{
            "session_id": "12345678-1234-1234-1234-123456789012",
            "learner_id": "87654321-4321-4321-4321-210987654321",
            "space": "LEARNING",
            "topic": "fractions-division",
            "student_state": {
                "attempts": 0,
                "mastery_score": 0.0,
                "consecutive_wrong": 0
            }
        }' 2>&1)
    
    if echo "$render_response" | grep -q "widgetId\|widgetType"; then
        print_success "Smart Line render endpoint is working!"
        echo "$render_response" | head -c 200
        echo "..."
    elif echo "$render_response" | grep -q "Connection refused"; then
        print_warning "Service is not responding yet (connection refused)"
        print_info "Wait a bit longer and try again"
    else
        print_warning "Unexpected response:"
        echo "$render_response" | head -c 200
    fi
}

main() {
    clear
    
    echo -e "${BLUE}"
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║                                                           ║"
    echo "║   Smart Line Widget Lake - Complete Docker Stack         ║"
    echo "║   GenAI-Native Widget Generation with ChatGPT            ║"
    echo "║                                                           ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    
    # Execute steps
    check_prerequisites
    build_docker_image
    start_services
    wait_for_services
    show_endpoints
    show_helpful_commands
    
    print_header "Final Setup"
    print_success "All services are running!"
    
    print_info "Attempting to test endpoint in 10 seconds..."
    sleep 10
    test_endpoint
    
    print_header "Ready to Use"
    echo -e "${GREEN}✓ Smart Line Widget Lake is running!${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Open http://localhost:8090 in your browser (Kafka UI)"
    echo "  2. Test render endpoint: see above"
    echo "  3. View logs: docker-compose -f $COMPOSE_FILE logs -f"
    echo "  4. Run tests: docker-compose -f $COMPOSE_FILE exec personalization-runtime mvn test"
    echo ""
    echo "To stop: docker-compose -f $COMPOSE_FILE down"
    echo ""
}

main "$@"
