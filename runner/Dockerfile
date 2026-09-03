FROM node:22-slim

ENV NODE_ENV=production
WORKDIR /app

COPY runner/package.json ./package.json
COPY runner/index.js ./index.js

RUN npm install --omit=dev --no-audit --no-fund

EXPOSE 10000
CMD ["node", "index.js"]
