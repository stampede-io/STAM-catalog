{{- define "catalog.fullname" -}}
{{- /*
Deliberately NOT the standard Helm "release-chart" fullname convention.
Every service's ConfigMap bakes in plain cross-service DNS names
(CATALOG_URL: http://catalog:8080, KAFKA_BOOTSTRAP_SERVERS: kafka:9092, ...)
— release-prefixing this Service name would break every one of those the
moment the umbrella chart installs with any release name other than
"catalog". Keep it the chart name, full stop.
*/ -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "catalog.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: Helm
{{- end -}}

{{- define "catalog.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "catalog.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "catalog.fullname" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}
