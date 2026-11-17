FROM nginx:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY dist /usr/share/nginx/html
ENV PORT=8080
EXPOSE 8080
