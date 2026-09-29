# Build the Vite SPA, then serve the static output with nginx.
FROM node:20-slim AS build
WORKDIR /app
ARG VITE_PLATFORM_URL=http://localhost:8080
ENV VITE_PLATFORM_URL=${VITE_PLATFORM_URL}
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
