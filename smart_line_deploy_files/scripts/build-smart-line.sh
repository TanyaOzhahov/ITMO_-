#!/bin/bash

# Smart Line Widget Lake - Docker Build Script
# Builds Docker image for BC-16 Personalization Runtime with Smart Line feature

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
IMAGE_NAME="adapstory/personalization-runtime"
IMAGE_TAG="0.1.0-smart-line"
REGISTRY="${REGISTRY:-localhost}"
FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

# Functions
print_header() {
    echo -e "${GREEN}================================${NC}"
    echo -e "${GREEN}$1${NC}"
    echo -e "${GREEN}================================${NC}"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_info() {
    echo -e "${YELLOW}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

# Main script
main() {
    print_header "Smart Line Widget Lake - Docker Build"

    # Check if Dockerfile exists
    if [ ! -f "adapstory-personalization-runtime/Dockerfile" ]; then
        print_error "Dockerfile not found at adapstory-personalization-runtime/Dockerfile"
        exit 1
    fi

    print_info "Building Docker image: ${FULL_IMAGE_NAME}"
    
    # Build the image
    docker build \
        --platform linux/amd64 \
        -t "${FULL_IMAGE_NAME}" \
        -f adapstory-personalization-runtime/Dockerfile \
        .
    
    if [ $? -eq 0 ]; then
        print_success "Docker image built successfully"
        print_success "Image name: ${FULL_IMAGE_NAME}"
    else
        print_error "Docker build failed"
        exit 1
    fi

    # Show image info
    print_header "Image Information"
    docker images "${IMAGE_NAME}:${IMAGE_TAG}"

    print_info "To run the container:"
    echo "  docker-compose -f docker-compose.smart-line.yml up -d"

    print_info "To verify the build:"
    echo "  docker run --rm ${FULL_IMAGE_NAME} java -version"

    print_success "Build completed!"
}

main "$@"
