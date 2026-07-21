# Deterministic image for the OmniApp presence listener (a plain always-on Node worker).
FROM node:22-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY . .
CMD ["npm", "start"]
