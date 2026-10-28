FROM node:22-alpine
WORKDIR /app
COPY api/package.json api/package-lock.json ./
RUN npm ci --omit=dev
COPY api/ ./
COPY web/ ./web/
EXPOSE 80
CMD ["node", "server.js"]
